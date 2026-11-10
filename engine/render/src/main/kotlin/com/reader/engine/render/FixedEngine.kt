package com.reader.engine.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.reader.core.model.Locator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Fixed-layout PDF engine.
 *
 * PdfRenderer access is serialized on [ioDispatcher]. Drawing only paints cached bitmaps;
 * cache misses schedule a background render and notify the UI on the main thread.
 */
class FixedEngine(
    private val pfd: ParcelFileDescriptor,
    private val pageWidthPx: Int,
    private val theme: String = "day",
    private val initialPageIndex: Int = 0,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onPageRendered: () -> Unit = {},
) : ReaderEngine {

    private val renderer = try {
        PdfRenderer(pfd)
    } catch (failure: Throwable) {
        runCatching { pfd.close() }
        throw failure
    }
    private val mutex = Mutex()
    private val renderScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val cleanupScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val renderJobs = ConcurrentHashMap<Int, Job>()
    private val pageWaiters = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false

    private val bitmapCache = object : LruCache<Int, Bitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: Int, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)

        override fun entryRemoved(
            evicted: Boolean,
            key: Int,
            oldValue: Bitmap,
            newValue: Bitmap?,
        ) {
            if (evicted && oldValue !== newValue && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val nightFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
    private val sepiaFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0.96f, 0f, 0f, 0f, 0f,
                0f, 0.90f, 0f, 0f, 0f,
                0f, 0f, 0.78f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )

    private val pageTotal: Int = renderer.pageCount

    private val _pageCount = kotlinx.coroutines.flow.MutableStateFlow(PageCountInfo(exact = pageTotal))
    override val pageCount: kotlinx.coroutines.flow.StateFlow<PageCountInfo> = _pageCount

    // Ready means the requested opening page has a completed bitmap, not merely that
    // PdfRenderer was constructed. The UI can safely leave its loading state then.
    private val _ready = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val ready: kotlinx.coroutines.flow.StateFlow<Boolean> = _ready

    init {
        if (pageTotal > 0) {
            val openingPage = initialPageIndex.coerceIn(0, pageTotal - 1)
            requestPage(openingPage)
            if (openingPage > 0) requestPage(openingPage - 1)
            if (openingPage + 1 < pageTotal) requestPage(openingPage + 1)
        }
    }

    override fun locatorOf(pageIndex: Int): Locator {
        val total = pageTotal.coerceAtLeast(1)
        val page = pageIndex.coerceIn(0, total - 1)
        return Locator(
            chapter = page,
            offset = 0,
            progression = page.toFloat() / total,
        )
    }

    override fun pageIndexOf(locator: Locator): Int =
        locator.chapter.coerceIn(0, (pageTotal - 1).coerceAtLeast(0))

    override fun drawPage(pageIndex: Int, canvas: Canvas, textColor: Int, backgroundColor: Int) {
        canvas.drawColor(backgroundColor)
        if (closed || pageIndex !in 0 until pageTotal) return

        if (pageIndex > 0) requestPage(pageIndex - 1)
        if (pageIndex + 1 < pageTotal) requestPage(pageIndex + 1)

        // Keep the cache lock for the complete draw operation. LruCache recycles an
        // evicted bitmap in entryRemoved(); releasing the lock after get() allowed a
        // background prefetch to recycle this bitmap while Canvas was still drawing it.
        val drawn = synchronized(bitmapCache) {
            val bitmap = bitmapCache.get(pageIndex)
            if (bitmap == null || bitmap.isRecycled) {
                false
            } else {
                paint.colorFilter = when (theme) {
                    "night" -> nightFilter
                    "sepia" -> sepiaFilter
                    else -> null
                }
                val scale = minOf(
                    canvas.width.toFloat() / bitmap.width.coerceAtLeast(1),
                    canvas.height.toFloat() / bitmap.height.coerceAtLeast(1),
                )
                val width = bitmap.width * scale
                val height = bitmap.height * scale
                val left = (canvas.width - width) / 2f
                val top = (canvas.height - height) / 2f
                canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), paint)
                true
            }
        }
        if (!drawn) requestPage(pageIndex)
    }

    /**
     * Suspends until the requested page bitmap is in cache. The reader can keep showing
     * the previous page until this returns, avoiding a blank frame during page changes.
     */
    override suspend fun awaitPage(pageIndex: Int): Boolean {
        if (closed || pageIndex !in 0 until pageTotal) return false
        synchronized(bitmapCache) {
            val cached = bitmapCache.get(pageIndex)
            if (cached != null && !cached.isRecycled) return true
        }
        val waiter = pageWaiters.computeIfAbsent(pageIndex) { CompletableDeferred() }
        // Close the race where a render finishes between the first cache check and
        // waiter registration.
        synchronized(bitmapCache) {
            val cached = bitmapCache.get(pageIndex)
            if (cached != null && !cached.isRecycled) {
                pageWaiters.remove(pageIndex)?.complete(true)
                return true
            }
        }
        requestPage(pageIndex)
        return waiter.await()
    }

    private fun requestPage(pageIndex: Int) {
        if (closed || pageIndex !in 0 until pageTotal) {
            pageWaiters.remove(pageIndex)?.complete(false)
            return
        }
        synchronized(bitmapCache) {
            val existing = bitmapCache.get(pageIndex)
            if (existing != null && !existing.isRecycled) {
                pageWaiters.remove(pageIndex)?.complete(true)
                return
            }
        }
        if (renderJobs.containsKey(pageIndex)) return

        val job = renderScope.launch(start = CoroutineStart.LAZY) {
            try {
                val bitmap = renderBitmap(pageIndex, pageWidthPx)
                if (bitmap != null && !closed) {
                    synchronized(bitmapCache) {
                        bitmapCache.put(pageIndex, bitmap)
                    }
                    if (pageIndex == initialPageIndex.coerceIn(0, (pageTotal - 1).coerceAtLeast(0))) {
                        _ready.value = true
                    }
                    pageWaiters.remove(pageIndex)?.complete(true)
                    mainHandler.post {
                        if (!closed) onPageRendered()
                    }
                } else {
                    bitmap?.recycle()
                    pageWaiters.remove(pageIndex)?.complete(false)
                }
            } catch (_: Exception) {
                pageWaiters.remove(pageIndex)?.complete(false)
            } finally {
                renderJobs.remove(pageIndex)
            }
        }
        if (renderJobs.putIfAbsent(pageIndex, job) == null) {
            job.start()
        } else {
            job.cancel()
        }
    }

    private suspend fun renderBitmap(pageIndex: Int, targetWidth: Int): Bitmap? = mutex.withLock {
        if (closed || pageIndex !in 0 until pageTotal) return null
        return try {
            renderer.openPage(pageIndex).use { page ->
                val requestedWidth = targetWidth.coerceAtLeast(1)
                val scale = requestedWidth.toFloat() / page.width.coerceAtLeast(1)
                val requestedHeight = (page.height * scale).toInt().coerceAtLeast(1)
                val requestedPixels = requestedWidth.toLong() * requestedHeight.toLong()
                val maxPixels = MAX_BITMAP_BYTES / 4L
                val reduction = if (requestedPixels > maxPixels) {
                    sqrt(maxPixels.toDouble() / requestedPixels.toDouble()).toFloat()
                } else {
                    1f
                }
                val width = (requestedWidth * reduction).toInt().coerceAtLeast(1)
                val height = (requestedHeight * reduction).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun renderThumbnail(pageIndex: Int, widthPx: Int): Bitmap? =
        renderBitmap(pageIndex, widthPx)

    override fun pageText(pageIndex: Int): String = ""

    override fun close() {
        if (closed) return
        closed = true
        renderJobs.values.forEach { it.cancel() }
        renderJobs.clear()
        renderScope.cancel()
        pageWaiters.values.forEach { it.complete(false) }
        pageWaiters.clear()
        mainHandler.removeCallbacksAndMessages(null)
        synchronized(bitmapCache) {
            bitmapCache.evictAll()
        }
        cleanupScope.launch {
            mutex.withLock {
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
            cleanupScope.cancel()
        }
    }

    private companion object {
        const val MAX_CACHE_KB = 32 * 1024
        const val MAX_BITMAP_BYTES = 16 * 1024 * 1024
    }
}

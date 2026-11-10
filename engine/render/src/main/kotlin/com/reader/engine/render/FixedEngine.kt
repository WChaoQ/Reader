package com.reader.engine.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.reader.core.model.Locator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
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
 * PdfRenderer access and bitmap creation are serialized and performed on an IO dispatcher.
 * The drawing callback only paints a cached bitmap; cache misses schedule a background render.
 */
class FixedEngine(
    private val pfd: ParcelFileDescriptor,
    private val pageWidthPx: Int,
    private val theme: String = "day",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onPageRendered: () -> Unit = {},
) : ReaderEngine {

    private val renderer = try {
        PdfRenderer(pfd)
    } catch (failure: Throwable) {
        pfd.close()
        throw failure
    }
    private val mutex = Mutex()
    private val renderScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val cleanupScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val renderJobs = ConcurrentHashMap<Int, Job>()
    @Volatile private var closed = false

    private val bitmapCache = object : LruCache<Int, Bitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: Int, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
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
    // Match the reader's sepia paper surface (#F5E6C8) without changing black text.
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

    private val _ready = kotlinx.coroutines.flow.MutableStateFlow(true)
    override val ready: kotlinx.coroutines.flow.StateFlow<Boolean> = _ready

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

        // Prefetch neighbours so continuous browse/swipe rarely shows an empty page.
        if (pageIndex > 0) requestPage(pageIndex - 1)
        if (pageIndex + 1 < pageTotal) requestPage(pageIndex + 1)

        val bitmap = bitmapCache.get(pageIndex)
        if (bitmap == null) {
            requestPage(pageIndex)
            return
        }

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
    }

    private fun requestPage(pageIndex: Int) {
        if (closed || pageIndex !in 0 until pageTotal || renderJobs.containsKey(pageIndex)) return
        if (bitmapCache.get(pageIndex) != null) return
        val job = renderScope.launch(start = CoroutineStart.LAZY) {
            try {
                val bitmap = renderBitmap(pageIndex, pageWidthPx)
                if (bitmap != null && !closed) {
                    bitmapCache.put(pageIndex, bitmap)
                    onPageRendered()
                } else {
                    bitmap?.recycle()
                }
            } catch (_: Exception) {
                // The reader remains usable; the page can be retried when requested again.
            } finally {
                renderJobs.remove(pageIndex)
            }
        }
        renderJobs[pageIndex] = job
        job.start()
    }

    private suspend fun renderBitmap(pageIndex: Int, targetWidth: Int): Bitmap? = mutex.withLock {
        if (closed || pageIndex !in 0 until pageTotal) return null
        return try {
            renderer.openPage(pageIndex).use { page ->
                val requestedWidth = targetWidth.coerceAtLeast(1)
                val scale = requestedWidth.toFloat() / page.width.coerceAtLeast(1)
                val requestedHeight = (page.height * scale).toInt().coerceAtLeast(1)
                val requestedPixels = requestedWidth.toLong() * requestedHeight.toLong()
                val maxPixels = MAX_BITMAP_BYTES / 2L // RGB_565 uses two bytes per pixel.
                val reduction = if (requestedPixels > maxPixels) {
                    sqrt(maxPixels.toDouble() / requestedPixels.toDouble()).toFloat()
                } else {
                    1f
                }
                val width = (requestedWidth * reduction).toInt().coerceAtLeast(1)
                val height = (requestedHeight * reduction).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
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
        bitmapCache.evictAll()
        cleanupScope.launch {
            mutex.withLock {
                runCatching { renderer.close() }
            }
            cleanupScope.cancel()
        }
    }

    private companion object {
        const val MAX_CACHE_KB = 24 * 1024
        const val MAX_BITMAP_BYTES = 12 * 1024 * 1024
    }
}

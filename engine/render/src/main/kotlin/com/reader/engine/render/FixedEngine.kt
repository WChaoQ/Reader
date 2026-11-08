package com.reader.engine.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.reader.core.model.Locator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Fixed-layout PDF engine (architecture §8). PdfRenderer access is Mutex-serialized. */
class FixedEngine(
    private val file: File,
    private val pageWidthPx: Int,
    private val nightMode: Boolean = false,
) : ReaderEngine {

    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val mutex = Mutex()

    private val _pageCount = MutableStateFlow(PageCountInfo(exact = renderer.pageCount))
    override val pageCount: StateFlow<PageCountInfo> = _pageCount.asStateFlow()

    private val _ready = MutableStateFlow(true)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()

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

    override fun locatorOf(pageIndex: Int): Locator {
        val total = renderer.pageCount.coerceAtLeast(1)
        return Locator(
            chapter = pageIndex.coerceIn(0, total - 1),
            offset = 0,
            progression = pageIndex.toFloat() / total,
        )
    }

    override fun pageIndexOf(locator: Locator): Int =
        locator.chapter.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))

    override fun drawPage(pageIndex: Int, canvas: Canvas, textColor: Int, backgroundColor: Int) {
        canvas.drawColor(backgroundColor)
        if (pageIndex !in 0 until renderer.pageCount) return
        if (!mutex.tryLock()) return
        try {
            renderer.openPage(pageIndex).use { page ->
                val scale = pageWidthPx.toFloat() / page.width
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val w = pageWidthPx.coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                paint.colorFilter = if (nightMode) nightFilter else null
                canvas.drawBitmap(bmp, Matrix(), paint)
                bmp.recycle()
            }
        } catch (_: Exception) {
        } finally {
            mutex.unlock()
        }
    }

    override fun pageText(pageIndex: Int): String = ""

    suspend fun renderThumbnail(pageIndex: Int, widthPx: Int): Bitmap? = mutex.withLock {
        if (pageIndex !in 0 until renderer.pageCount) return null
        return try {
            renderer.openPage(pageIndex).use { page ->
                val scale = widthPx.toFloat() / page.width
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.RGB_565)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        renderer.close()
        pfd.close()
    }
}

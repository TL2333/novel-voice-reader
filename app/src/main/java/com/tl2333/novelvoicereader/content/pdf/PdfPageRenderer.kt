package com.tl2333.novelvoicereader.content.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/** Display-only PDF adapter. Text extraction and narration never depend on this renderer. */
class PdfPageRenderer(private val file: File) {
    fun pageCount(): Int = open { it.pageCount }

    fun render(pageNumber: Int, targetWidth: Int): Bitmap = open { renderer ->
        require(pageNumber in 1..renderer.pageCount)
        renderer.openPage(pageNumber - 1).use { page ->
            val width = targetWidth.coerceIn(480, 1800)
            val height = (page.height.toDouble() * width / page.width).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    private inline fun <T> open(block: (PdfRenderer) -> T): T {
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use(block)
        }
    }
}

package com.tl2333.novelvoicereader.content.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import com.tl2333.novelvoicereader.content.model.DocumentBounds
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfTextExtractor(
    private val ocrFactory: () -> PdfOcrProvider = ::BundledMlKitPdfOcrProvider,
) {
    suspend fun extract(file: File): PdfExtractionResult = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() > 0L) { "PDF file is empty" }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount > 0) { "PDF has no pages" }
                val native = if (Build.VERSION.SDK_INT >= 35) extractEmbeddedText(renderer) else NativeExtraction(emptyList(), BooleanArray(renderer.pageCount))
                val likelyScanned = Build.VERSION.SDK_INT < 35 || isLikelyScanned(renderer.pageCount, native.fragments, native.hasImages)
                if (!likelyScanned) {
                    return@withContext PdfExtractionResult(native.fragments, renderer.pageCount, likelyScanned = false, usedOcr = false)
                }

                val merged = native.fragments.toMutableList()
                ocrFactory().use { ocr ->
                    repeat(renderer.pageCount) { index ->
                        val pageNumber = index + 1
                        val embeddedCharacters = native.fragments.asSequence()
                            .filter { it.page == pageNumber }
                            .sumOf { it.text.length }
                        if (embeddedCharacters >= MIN_EMBEDDED_CHARACTERS_PER_PAGE) return@repeat
                        renderer.openPage(index).use { page ->
                            val bitmap = renderForOcr(page)
                            try {
                                merged += ocr.recognize(bitmap, pageNumber, page.width.toFloat(), page.height.toFloat())
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
                PdfExtractionResult(merged, renderer.pageCount, likelyScanned = true, usedOcr = true)
            }
        }
    }

    @RequiresApi(35)
    private fun extractEmbeddedText(renderer: PdfRenderer): NativeExtraction {
        val fragments = mutableListOf<PdfTextFragment>()
        val hasImages = BooleanArray(renderer.pageCount)
        repeat(renderer.pageCount) { index ->
            renderer.openPage(index).use { page ->
                hasImages[index] = page.imageContents.isNotEmpty()
                page.textContents.forEach { content ->
                    val text = content.text.trim()
                    if (text.isNotBlank()) {
                        fragments += PdfTextFragment(
                            page = index + 1,
                            text = text,
                            bounds = content.bounds.unionOrNull(),
                            pageWidth = page.width.toFloat(),
                            pageHeight = page.height.toFloat(),
                        )
                    }
                }
            }
        }
        return NativeExtraction(fragments, hasImages)
    }

    private fun isLikelyScanned(pageCount: Int, fragments: List<PdfTextFragment>, hasImages: BooleanArray): Boolean {
        var consecutive = 0
        repeat(pageCount) { index ->
            val characters = fragments.asSequence().filter { it.page == index + 1 }.sumOf { it.text.length }
            consecutive = if (characters < MIN_EMBEDDED_CHARACTERS_PER_PAGE && hasImages.getOrElse(index) { false }) consecutive + 1 else 0
            if (consecutive >= minOf(2, pageCount)) return true
        }
        return false
    }

    private fun renderForOcr(page: PdfRenderer.Page): Bitmap {
        val scale = (OCR_LONG_EDGE / maxOf(page.width, page.height).toFloat()).coerceAtLeast(1f)
        val bitmap = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private fun List<RectF>.unionOrNull(): DocumentBounds? {
        val first = firstOrNull() ?: return null
        var left = first.left
        var top = first.top
        var right = first.right
        var bottom = first.bottom
        drop(1).forEach { rect ->
            left = minOf(left, rect.left)
            top = minOf(top, rect.top)
            right = maxOf(right, rect.right)
            bottom = maxOf(bottom, rect.bottom)
        }
        return DocumentBounds(left, top, right, bottom)
    }

    private data class NativeExtraction(val fragments: List<PdfTextFragment>, val hasImages: BooleanArray)

    companion object {
        private const val MIN_EMBEDDED_CHARACTERS_PER_PAGE = 40
        private const val OCR_LONG_EDGE = 2048f
    }
}

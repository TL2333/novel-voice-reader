package com.tl2333.novelvoicereader.content.pdf

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tl2333.novelvoicereader.content.model.DocumentBounds
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class PdfOcrException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface PdfOcrProvider : AutoCloseable {
    suspend fun recognize(bitmap: Bitmap, page: Int, pageWidth: Float, pageHeight: Float): List<PdfTextFragment>
}

/** Both recognizers are bundled in the APK; recognition never requires a model download or cloud request. */
class BundledMlKitPdfOcrProvider : PdfOcrProvider {
    private val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(
        bitmap: Bitmap,
        page: Int,
        pageWidth: Float,
        pageHeight: Float,
    ): List<PdfTextFragment> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val chineseResult = runCatching { chinese.processAwait(image) }
        val latinResult = runCatching { latin.processAwait(image) }
        val selected = listOfNotNull(chineseResult.getOrNull(), latinResult.getOrNull())
            .maxByOrNull { result -> result.text.count(Char::isLetterOrDigit) }
            ?: throw PdfOcrException(
                "本地 OCR 初始化或识别失败",
                chineseResult.exceptionOrNull() ?: latinResult.exceptionOrNull(),
            )
        val scaleX = pageWidth / bitmap.width.toFloat()
        val scaleY = pageHeight / bitmap.height.toFloat()
        return selected.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            line.text.takeIf(String::isNotBlank)?.let { text ->
                PdfTextFragment(
                    page = page,
                    text = text,
                    bounds = DocumentBounds(box.left * scaleX, box.top * scaleY, box.right * scaleX, box.bottom * scaleY),
                    pageWidth = pageWidth,
                    pageHeight = pageHeight,
                    confidence = line.confidence,
                    source = PdfTextSource.OCR,
                )
            }
        }
    }

    override fun close() {
        chinese.close()
        latin.close()
    }
}

private suspend fun TextRecognizer.processAwait(image: InputImage): Text = suspendCancellableCoroutine { continuation ->
    process(image)
        .addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
        .addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
}

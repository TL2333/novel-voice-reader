package com.tl2333.novelvoicereader.content.pdf

import com.tl2333.novelvoicereader.content.model.BlockType
import com.tl2333.novelvoicereader.content.model.DocumentBounds

enum class PdfTextSource { EMBEDDED_TEXT, OCR }

data class PdfTextFragment(
    val page: Int,
    val text: String,
    val bounds: DocumentBounds?,
    val pageWidth: Float,
    val pageHeight: Float,
    val confidence: Float? = null,
    val source: PdfTextSource = PdfTextSource.EMBEDDED_TEXT,
)

data class PdfResolvedBlock(
    val page: Int,
    val text: String,
    val bounds: DocumentBounds?,
    val type: BlockType,
    val confidence: Float?,
    val source: PdfTextSource,
)

data class PdfExtractionResult(
    val fragments: List<PdfTextFragment>,
    val pageCount: Int,
    val likelyScanned: Boolean,
    val usedOcr: Boolean,
)

package com.tl2333.novelvoicereader.content.pdf

import com.tl2333.novelvoicereader.content.model.CanonicalDocument
import com.tl2333.novelvoicereader.content.model.ContentBlock
import com.tl2333.novelvoicereader.content.model.DocumentLocation
import com.tl2333.novelvoicereader.content.model.DocumentSection
import com.tl2333.novelvoicereader.content.model.SourceType
import com.tl2333.novelvoicereader.filesystem.Sha256
import java.io.File

class PdfImporter(
    private val extractor: PdfTextExtractor = PdfTextExtractor(),
    private val readingOrder: PdfReadingOrderResolver = PdfReadingOrderResolver(),
) {
    suspend fun import(
        file: File,
        sourceUri: String = file.toURI().toString(),
        displayTitle: String = file.nameWithoutExtension,
    ): CanonicalDocument {
        require(file.isFile && file.extension.equals("pdf", ignoreCase = true))
        val extraction = extractor.extract(file)
        val resolved = readingOrder.resolve(extraction.fragments)
        if (resolved.isEmpty()) error("PDF 中没有可朗读文字；本地 OCR 未识别到内容")
        val sectionPages = resolved.map(PdfResolvedBlock::page).distinct()
        val sections = sectionPages.mapIndexed { index, page -> DocumentSection("page-$page", "第 $page 页", index) }
        val blocks = resolved.mapIndexed { index, block ->
            val id = "block-$index"
            ContentBlock(
                id = id,
                sectionId = "page-${block.page}",
                type = block.type,
                text = block.text,
                order = index,
                anchor = DocumentLocation(
                    sourceType = SourceType.PDF,
                    sectionId = "page-${block.page}",
                    blockId = id,
                    page = block.page,
                    bounds = block.bounds,
                ),
                metadata = buildMap {
                    put("textSource", block.source.name)
                    block.confidence?.let { put("confidence", it.toString()) }
                },
            )
        }
        val hash = Sha256.hash(file)
        return CanonicalDocument(
            id = hash,
            sourceType = SourceType.PDF,
            title = displayTitle.ifBlank { "Untitled PDF" },
            author = null,
            sourceUri = sourceUri,
            contentHash = hash,
            sections = sections,
            blocks = blocks,
            metadata = mapOf(
                "pageCount" to extraction.pageCount.toString(),
                "likelyScanned" to extraction.likelyScanned.toString(),
                "usedBundledOcr" to extraction.usedOcr.toString(),
                "pdfEngine" to "android.graphics.pdf.PdfRenderer",
            ),
        )
    }
}

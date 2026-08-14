package com.tl2333.novelvoicereader.content.docx

import com.tl2333.novelvoicereader.content.model.BlockType
import com.tl2333.novelvoicereader.content.model.CanonicalDocument
import com.tl2333.novelvoicereader.content.model.ContentBlock
import com.tl2333.novelvoicereader.content.model.DocumentLocation
import com.tl2333.novelvoicereader.content.model.DocumentSection
import com.tl2333.novelvoicereader.content.model.SourceType
import com.tl2333.novelvoicereader.filesystem.Sha256
import java.io.File

class DocxImporter(private val parser: DocxParser = DocxParser()) {
    fun import(
        file: File,
        sourceUri: String = file.toURI().toString(),
        fallbackTitle: String = file.nameWithoutExtension,
    ): CanonicalDocument {
        require(file.isFile && file.extension.equals("docx", ignoreCase = true))
        val paragraphs = parser.parse(file)
        if (paragraphs.isEmpty()) error("DOCX contains no readable paragraphs")
        val sections = mutableListOf(DocumentSection("section-0", null, 0))
        var sectionId = sections.single().id
        val blocks = mutableListOf<ContentBlock>()
        paragraphs.forEach { paragraph ->
            if (paragraph.type == BlockType.HEADING || paragraph.type == BlockType.TITLE) {
                sectionId = "section-${sections.size}"
                sections += DocumentSection(sectionId, paragraph.text, sections.size)
            }
            val blockId = "block-${blocks.size}"
            blocks += ContentBlock(
                id = blockId,
                sectionId = sectionId,
                type = paragraph.type,
                text = paragraph.text,
                order = blocks.size,
                anchor = DocumentLocation(
                    sourceType = SourceType.DOCX,
                    sectionId = sectionId,
                    blockId = blockId,
                    paragraphIndex = paragraph.paragraphIndex,
                ),
            )
            if (paragraph.pageBreakAfter) {
                val pageBreakId = "block-${blocks.size}"
                blocks += ContentBlock(
                    pageBreakId,
                    sectionId,
                    BlockType.PAGE_BREAK,
                    "\u000c",
                    blocks.size,
                    DocumentLocation(SourceType.DOCX, sectionId = sectionId, blockId = pageBreakId, paragraphIndex = paragraph.paragraphIndex),
                )
            }
        }
        val hash = Sha256.hash(file)
        return CanonicalDocument(
            id = hash,
            sourceType = SourceType.DOCX,
            title = paragraphs.firstOrNull { it.type == BlockType.TITLE }?.text
                ?: fallbackTitle.ifBlank { "Untitled DOCX" },
            author = null,
            sourceUri = sourceUri,
            contentHash = hash,
            sections = sections,
            blocks = blocks,
            metadata = mapOf("paragraphCount" to paragraphs.size.toString()),
        )
    }
}

package com.tl2333.novelvoicereader.content.txt

import com.tl2333.novelvoicereader.content.model.BlockType
import com.tl2333.novelvoicereader.content.model.CanonicalDocument
import com.tl2333.novelvoicereader.content.model.ContentBlock
import com.tl2333.novelvoicereader.content.model.DocumentLocation
import com.tl2333.novelvoicereader.content.model.DocumentSection
import com.tl2333.novelvoicereader.content.model.SourceType
import com.tl2333.novelvoicereader.filesystem.Sha256
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStreamReader

class TxtImporter {
    fun import(
        file: File,
        sourceUri: String = file.toURI().toString(),
        displayTitle: String = file.nameWithoutExtension,
    ): CanonicalDocument {
        require(file.isFile)
        val detected = file.inputStream().buffered().use(TxtCharsetDetector::detect)
        val sections = mutableListOf<DocumentSection>()
        val blocks = mutableListOf<ContentBlock>()
        var sectionId = "section-0"
        sections += DocumentSection(sectionId, null, 0)
        var absoluteOffset = 0
        file.inputStream().use { raw ->
            val buffered = BufferedInputStream(raw)
            repeat(detected.bomBytes) { check(buffered.read() >= 0) }
            InputStreamReader(buffered, detected.charset).buffered(64 * 1024).useLines { sequence ->
                val lines = sequence.iterator()
                var previousBlank = true
                var current: String? = if (lines.hasNext()) lines.next() else null
                while (current != null) {
                    val next = if (lines.hasNext()) lines.next() else null
                    val rawLine = current
                    val text = rawLine.trim()
                    val nextBlank = next == null || next.isBlank()
                    if (text.isNotEmpty()) {
                        val isHeading = TxtChapterDetector.isChapter(text, previousBlank, nextBlank)
                        if (isHeading) {
                            sectionId = "section-${sections.size}"
                            sections += DocumentSection(sectionId, text, sections.size)
                        }
                        val blockId = "block-${blocks.size}"
                        blocks += ContentBlock(
                            id = blockId,
                            sectionId = sectionId,
                            type = if (isHeading) BlockType.HEADING else BlockType.PARAGRAPH,
                            text = text,
                            order = blocks.size,
                            anchor = DocumentLocation(
                                sourceType = SourceType.TXT,
                                sectionId = sectionId,
                                blockId = blockId,
                                charStart = absoluteOffset,
                                charEnd = absoluteOffset + rawLine.length,
                            ),
                        )
                    }
                    absoluteOffset += rawLine.length + 1
                    previousBlank = rawLine.isBlank()
                    current = next
                }
            }
        }
        val hash = Sha256.hash(file)
        if (blocks.isEmpty()) error("TXT contains no readable text")
        return CanonicalDocument(
            id = hash,
            sourceType = SourceType.TXT,
            title = displayTitle.ifBlank { "Untitled TXT" },
            author = null,
            sourceUri = sourceUri,
            contentHash = hash,
            sections = sections,
            blocks = blocks,
            metadata = mapOf(
                "charset" to detected.charset.name(),
                "characterCount" to absoluteOffset.toString(),
            ),
        )
    }
}

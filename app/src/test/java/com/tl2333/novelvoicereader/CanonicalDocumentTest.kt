package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.content.model.BlockType
import com.tl2333.novelvoicereader.content.model.CanonicalDocument
import com.tl2333.novelvoicereader.content.model.ContentBlock
import com.tl2333.novelvoicereader.content.model.DocumentLocation
import com.tl2333.novelvoicereader.content.model.DocumentSection
import com.tl2333.novelvoicereader.content.model.SourceType
import org.junit.Test

class CanonicalDocumentTest {
    @Test
    fun acceptsOrderedFormatNeutralBlocks() {
        val section = DocumentSection("s1", "第一章", 0)
        val block = ContentBlock(
            id = "b1",
            sectionId = section.id,
            type = BlockType.PARAGRAPH,
            text = "夜色沉了。",
            order = 0,
            anchor = DocumentLocation(SourceType.TXT, sectionId = "s1", blockId = "b1", charStart = 0, charEnd = 5),
        )
        val document = CanonicalDocument("d1", SourceType.TXT, "测试", null, "content://test", "a".repeat(64), listOf(section), listOf(block))
        assertThat(document.blocks.single().anchor.sourceType).isEqualTo(SourceType.TXT)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlocksFromUnknownSections() {
        CanonicalDocument(
            "d1", SourceType.TXT, "测试", null, "content://test", "a".repeat(64),
            listOf(DocumentSection("s1", null, 0)),
            listOf(ContentBlock("b1", "missing", BlockType.PARAGRAPH, "正文", 0, DocumentLocation(SourceType.TXT, blockId = "b1"))),
        )
    }
}

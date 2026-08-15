package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.content.model.BlockType
import com.tl2333.novelvoicereader.content.model.CanonicalDocument
import com.tl2333.novelvoicereader.content.model.CanonicalDocumentCodec
import com.tl2333.novelvoicereader.content.model.ContentBlock
import com.tl2333.novelvoicereader.content.model.DocumentLocation
import com.tl2333.novelvoicereader.content.model.DocumentSection
import com.tl2333.novelvoicereader.content.model.SourceType
import org.junit.Test

class CanonicalDocumentCodecTest {
    @Test
    fun roundTripsCanonicalContentWithoutParserObjects() {
        val document = CanonicalDocument(
            "d", SourceType.WEB, "标题", "作者", "https://example.test", "c".repeat(64),
            listOf(DocumentSection("s", "标题", 0)),
            listOf(ContentBlock("b", "s", BlockType.PARAGRAPH, "正文", 0, DocumentLocation(SourceType.WEB, "s", "b", snapshotId = "snap"))),
            mapOf("domain" to "example.test"),
        )
        assertThat(CanonicalDocumentCodec.decode(CanonicalDocumentCodec.encode(document))).isEqualTo(document)
    }
}

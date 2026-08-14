package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.content.model.BlockType
import com.tl2333.novelvoicereader.content.model.CanonicalDocument
import com.tl2333.novelvoicereader.content.model.ContentBlock
import com.tl2333.novelvoicereader.content.model.DocumentLocation
import com.tl2333.novelvoicereader.content.model.DocumentSection
import com.tl2333.novelvoicereader.content.model.SourceType
import com.tl2333.novelvoicereader.narration.SpeechPlanner
import org.junit.Test

class SpeechSegmenterTest {
    @Test
    fun preservesDialogueAndStableSourceAnchors() {
        val text = "她问：“你到底想做什么？”他没有回答。"
        val document = CanonicalDocument(
            "doc", SourceType.TXT, "测试", null, "content://doc", "b".repeat(64),
            listOf(DocumentSection("s", null, 0)),
            listOf(ContentBlock("b", "s", BlockType.PARAGRAPH, text, 0, DocumentLocation(SourceType.TXT, "s", "b"))),
        )
        val segments = SpeechPlanner().plan(document)
        assertThat(segments.map { it.text }).contains("她问：“你到底想做什么？”")
        assertThat(segments.map { it.order }).containsExactlyElementsIn(segments.indices).inOrder()
        assertThat(segments.all { it.anchor.charStart != null && it.anchor.charEnd != null }).isTrue()
    }
}

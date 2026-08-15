package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.content.model.DocumentLocation
import com.tl2333.novelvoicereader.content.model.SourceType
import com.tl2333.novelvoicereader.narration.ChunkAssembler
import com.tl2333.novelvoicereader.narration.SegmentPcm
import com.tl2333.novelvoicereader.narration.SpeechSegment
import org.junit.Test

class ChunkAssemblerTest {
    @Test
    fun joinsAdjacentSegmentsWithPlannedSilenceAndTimeline() {
        val parts = (0..2).map { index ->
            val segment = SpeechSegment("s$index", "d", "section", "b$index", index, "文本$index。", DocumentLocation(SourceType.TXT, blockId = "b$index"), 100)
            SegmentPcm(segment, sampleRate = 1_000, samples = ShortArray(1_000) { (index + 1).toShort() })
        }
        val chunk = ChunkAssembler.assemble(parts)
        assertThat(chunk.samples.size).isEqualTo(3_300)
        assertThat(chunk.timeline.map { it.startMs }).containsExactly(0L, 1_100L, 2_200L).inOrder()
        assertThat(chunk.timeline.map { it.endMs }).containsExactly(1_000L, 2_100L, 3_200L).inOrder()
    }
}

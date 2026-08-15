package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.narration.NarrationTrace
import com.tl2333.novelvoicereader.narration.NarrationTraceEvent
import com.tl2333.novelvoicereader.narration.NarrationTraceRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NarrationTraceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun writesRequiredJsonlFieldsWithoutDocumentText() {
        val trace = NarrationTrace(temporaryFolder.root, "document-1")
        trace.record(
            NarrationTraceRecord(
                event = NarrationTraceEvent.SYNTHESIS_FINISH,
                segmentId = "segment-2",
                generationMs = 400,
                audioDurationMs = 1_000,
                rtf = 0.4,
                playbackSpeed = 2f,
                bufferWallMs = 45_000,
                queueDepth = 3,
            ),
        )

        val value = Json.parseToJsonElement(trace.file.readLines().single()).jsonObject
        assertThat(value.keys).containsAtLeast("timestamp", "sessionId", "documentId", "segmentId", "event", "generationMs", "audioDurationMs", "rtf", "playbackSpeed", "bufferWallMs", "queueDepth", "memoryMb")
        assertThat(value.keys).doesNotContain("text")
    }
}

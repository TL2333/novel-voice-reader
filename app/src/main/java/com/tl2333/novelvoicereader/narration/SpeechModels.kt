package com.tl2333.novelvoicereader.narration

import com.tl2333.novelvoicereader.content.model.DocumentLocation

data class SpeechSegment(
    val id: String,
    val documentId: String,
    val sectionId: String,
    val blockId: String,
    val order: Int,
    val text: String,
    val anchor: DocumentLocation,
    val plannedPauseMs: Long,
) {
    init {
        require(id.isNotBlank() && documentId.isNotBlank() && sectionId.isNotBlank() && blockId.isNotBlank())
        require(order >= 0)
        require(text.isNotBlank())
        require(plannedPauseMs >= 0)
    }
}

data class AudioArtifact(
    val cacheKey: String,
    val path: String,
    val sampleRate: Int,
    val durationMs: Long,
    val segmentIds: List<String>,
    val createdAt: Long,
) {
    init {
        require(cacheKey.isNotBlank() && path.isNotBlank())
        require(sampleRate > 0 && durationMs > 0)
        require(segmentIds.isNotEmpty() && segmentIds.distinct().size == segmentIds.size)
    }
}

data class ChunkTimelineEntry(
    val segmentId: String,
    val startMs: Long,
    val endMs: Long,
    val anchor: DocumentLocation,
) {
    init {
        require(segmentId.isNotBlank())
        require(startMs >= 0 && endMs >= startMs)
    }
}

data class PlaybackChunk(
    val id: String,
    val sampleRate: Int,
    val samples: ShortArray,
    val timeline: List<ChunkTimelineEntry>,
) {
    val durationMs: Long = samples.size * 1_000L / sampleRate
}

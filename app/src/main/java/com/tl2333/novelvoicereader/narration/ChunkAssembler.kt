package com.tl2333.novelvoicereader.narration

import com.tl2333.novelvoicereader.filesystem.Sha256

data class SegmentPcm(val segment: SpeechSegment, val sampleRate: Int, val samples: ShortArray) {
    init { require(sampleRate > 0 && samples.isNotEmpty()) }
}

object ChunkAssembler {
    const val MIN_SEGMENTS = 3
    const val MAX_SEGMENTS = 8
    const val TARGET_MIN_DURATION_MS = 12_000L
    const val TARGET_MAX_DURATION_MS = 24_000L

    fun assemble(parts: List<SegmentPcm>): PlaybackChunk {
        require(parts.isNotEmpty() && parts.size <= MAX_SEGMENTS)
        val sampleRate = parts.first().sampleRate
        require(parts.all { it.sampleRate == sampleRate })
        require(parts.zipWithNext().all { (first, second) -> second.segment.order == first.segment.order + 1 })
        val totalSamples = parts.sumOf { part ->
            part.samples.size + ((part.segment.plannedPauseMs * sampleRate) / 1_000L).toInt()
        }
        val output = ShortArray(totalSamples)
        val timeline = ArrayList<ChunkTimelineEntry>(parts.size)
        var cursor = 0
        parts.forEach { part ->
            val start = cursor * 1_000L / sampleRate
            part.samples.copyInto(output, cursor)
            cursor += part.samples.size
            val end = cursor * 1_000L / sampleRate
            timeline += ChunkTimelineEntry(part.segment.id, start, end, part.segment.anchor)
            cursor += ((part.segment.plannedPauseMs * sampleRate) / 1_000L).toInt()
        }
        val id = Sha256.hash(timeline.joinToString("\u0000") { it.segmentId })
        return PlaybackChunk(id, sampleRate, output, timeline)
    }
}

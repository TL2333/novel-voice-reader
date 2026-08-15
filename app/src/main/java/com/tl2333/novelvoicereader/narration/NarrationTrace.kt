package com.tl2333.novelvoicereader.narration

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class NarrationTraceEvent {
    SESSION_START, SEGMENT_QUEUED, SYNTHESIS_START, SYNTHESIS_FINISH, CACHE_HIT, CACHE_MISS,
    CHUNK_READY, PLAYBACK_START, PLAYBACK_END, BUFFER_LOW, BUFFER_EMPTY, BUFFER_RECOVERED,
    TTS_PROCESS_DIED, ERROR, SESSION_END,
}

data class NarrationTraceRecord(
    val event: NarrationTraceEvent,
    val segmentId: String? = null,
    val generationMs: Long? = null,
    val audioDurationMs: Long? = null,
    val rtf: Double? = null,
    val playbackSpeed: Float = 1f,
    val bufferWallMs: Long? = null,
    val queueDepth: Int? = null,
    val chunkId: String? = null,
    val plannedPauseMs: Long? = null,
    val unplannedGapMs: Long? = null,
    val errorCode: String? = null,
)

/** Privacy-safe JSONL trace. It intentionally has no text field. */
class NarrationTrace(root: File, private val documentId: String) {
    val sessionId: String = UUID.randomUUID().toString()
    val file: File = File(root, "$sessionId.jsonl").apply { parentFile?.mkdirs() }

    @Synchronized
    fun record(value: NarrationTraceRecord) {
        val runtime = Runtime.getRuntime()
        val memoryMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)
        val json = buildJsonObject {
            put("timestamp", System.currentTimeMillis())
            put("sessionId", sessionId)
            put("documentId", documentId)
            value.segmentId?.let { put("segmentId", it) }
            put("event", value.event.name)
            value.generationMs?.let { put("generationMs", it) }
            value.audioDurationMs?.let { put("audioDurationMs", it) }
            value.rtf?.let { put("rtf", it) }
            put("playbackSpeed", value.playbackSpeed)
            value.bufferWallMs?.let { put("bufferWallMs", it) }
            value.queueDepth?.let { put("queueDepth", it) }
            value.chunkId?.let { put("chunkId", it) }
            value.plannedPauseMs?.let { put("plannedPauseMs", it) }
            value.unplannedGapMs?.let { put("unplannedGapMs", it) }
            put("memoryMb", memoryMb)
            value.errorCode?.let { put("errorCode", it) }
        }
        file.appendText(json.toString() + "\n", Charsets.UTF_8)
    }
}

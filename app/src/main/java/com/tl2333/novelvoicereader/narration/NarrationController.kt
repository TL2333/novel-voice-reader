package com.tl2333.novelvoicereader.narration

enum class NarrationState { IDLE, PREPARING, WARMING_BUFFER, BUFFERING, PLAYING, PAUSED, SEEKING, STOPPING, ERROR, RELEASED }
enum class NarrationErrorCode { SYNTHESIS_FAILED, PLAYBACK_FAILED, TTS_ENGINE_DIED, INVALID_TRANSITION }

sealed interface NarrationIntent {
    data class Start(val documentId: String, val segmentIndex: Int = 0) : NarrationIntent
    data object Pause : NarrationIntent
    data object Resume : NarrationIntent
    data object Stop : NarrationIntent
    data object Next : NarrationIntent
    data object Previous : NarrationIntent
    data class Seek(val segmentIndex: Int) : NarrationIntent
    data class SetPlaybackSpeed(val speed: Float) : NarrationIntent
    data class SetVoice(val voiceId: String) : NarrationIntent
    data class SetStyle(val styleId: String) : NarrationIntent
}

sealed interface NarrationEvent {
    data class DocumentChanged(val documentId: String) : NarrationEvent
    data class SynthesisStarted(val segmentId: String) : NarrationEvent
    data class SynthesisCompleted(val segmentId: String, val generatedMediaMs: Long) : NarrationEvent
    data class SynthesisFailed(val message: String) : NarrationEvent
    data object PlaybackStarted : NarrationEvent
    data class PlaybackPosition(val segmentIndex: Int, val positionMs: Long) : NarrationEvent
    data object PlaybackCompleted : NarrationEvent
    data class PlaybackFailed(val message: String) : NarrationEvent
    data object BufferLow : NarrationEvent
    data object BufferRecovered : NarrationEvent
    data object TtsProcessDied : NarrationEvent
}

data class NarrationSnapshot(
    val state: NarrationState = NarrationState.IDLE,
    val documentId: String? = null,
    val segmentIndex: Int = 0,
    val playbackSpeed: Float = 1f,
    val voiceId: String = "3",
    val styleId: String = "neutral",
    val generatedMediaMs: Long = 0,
    val positionMs: Long = 0,
    val errorCode: NarrationErrorCode? = null,
    val errorMessage: String? = null,
)

interface NarrationPlaybackPort {
    fun setPlaybackSpeed(speed: Float)
    fun play()
    fun pause()
    fun stop()
}

class NarrationController(private val playback: NarrationPlaybackPort) {
    private val supportedSpeeds = setOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    @Volatile
    var snapshot: NarrationSnapshot = NarrationSnapshot()
        private set

    @Synchronized
    fun send(intent: NarrationIntent): NarrationSnapshot {
        check(snapshot.state != NarrationState.RELEASED) { "NarrationController is released" }
        snapshot = when (intent) {
            is NarrationIntent.Start -> {
                require(intent.documentId.isNotBlank() && intent.segmentIndex >= 0)
                snapshot.copy(state = NarrationState.PREPARING, documentId = intent.documentId, segmentIndex = intent.segmentIndex, generatedMediaMs = 0, positionMs = 0, errorCode = null, errorMessage = null)
            }
            NarrationIntent.Pause -> if (snapshot.state == NarrationState.PLAYING || snapshot.state == NarrationState.BUFFERING) {
                playback.pause()
                snapshot.copy(state = NarrationState.PAUSED)
            } else snapshot
            NarrationIntent.Resume -> if (snapshot.state == NarrationState.PAUSED) {
                playback.play()
                snapshot.copy(state = if (snapshot.generatedMediaMs > 0) NarrationState.PLAYING else NarrationState.BUFFERING)
            } else snapshot
            NarrationIntent.Stop -> {
                playback.stop()
                snapshot.copy(state = NarrationState.IDLE, generatedMediaMs = 0, positionMs = 0)
            }
            NarrationIntent.Next -> snapshot.copy(state = NarrationState.SEEKING, segmentIndex = snapshot.segmentIndex + 1, positionMs = 0)
            NarrationIntent.Previous -> snapshot.copy(state = NarrationState.SEEKING, segmentIndex = (snapshot.segmentIndex - 1).coerceAtLeast(0), positionMs = 0)
            is NarrationIntent.Seek -> {
                require(intent.segmentIndex >= 0)
                snapshot.copy(state = NarrationState.SEEKING, segmentIndex = intent.segmentIndex, positionMs = 0)
            }
            is NarrationIntent.SetPlaybackSpeed -> {
                require(intent.speed in supportedSpeeds)
                playback.setPlaybackSpeed(intent.speed)
                snapshot.copy(playbackSpeed = intent.speed)
            }
            is NarrationIntent.SetVoice -> snapshot.copy(voiceId = intent.voiceId.also { require(it.isNotBlank()) })
            is NarrationIntent.SetStyle -> snapshot.copy(styleId = intent.styleId.also { require(it.isNotBlank()) })
        }
        return snapshot
    }

    @Synchronized
    fun accept(event: NarrationEvent): NarrationSnapshot {
        if (snapshot.state == NarrationState.RELEASED) return snapshot
        snapshot = when (event) {
            is NarrationEvent.DocumentChanged -> snapshot.copy(documentId = event.documentId, state = NarrationState.PREPARING)
            is NarrationEvent.SynthesisStarted -> if (snapshot.state == NarrationState.PREPARING) snapshot.copy(state = NarrationState.WARMING_BUFFER) else snapshot
            is NarrationEvent.SynthesisCompleted -> snapshot.copy(generatedMediaMs = snapshot.generatedMediaMs + event.generatedMediaMs)
            is NarrationEvent.SynthesisFailed -> snapshot.error(NarrationErrorCode.SYNTHESIS_FAILED, event.message)
            NarrationEvent.PlaybackStarted -> {
                playback.play()
                snapshot.copy(state = NarrationState.PLAYING)
            }
            is NarrationEvent.PlaybackPosition -> snapshot.copy(segmentIndex = event.segmentIndex, positionMs = event.positionMs)
            NarrationEvent.PlaybackCompleted -> snapshot.copy(state = NarrationState.IDLE, generatedMediaMs = 0, positionMs = 0)
            is NarrationEvent.PlaybackFailed -> snapshot.error(NarrationErrorCode.PLAYBACK_FAILED, event.message)
            NarrationEvent.BufferLow -> snapshot.copy(state = NarrationState.BUFFERING)
            NarrationEvent.BufferRecovered -> if (snapshot.state == NarrationState.BUFFERING) snapshot.copy(state = NarrationState.PLAYING) else snapshot
            NarrationEvent.TtsProcessDied -> {
                playback.stop()
                snapshot.error(NarrationErrorCode.TTS_ENGINE_DIED, "TTS engine process died")
            }
        }
        return snapshot
    }

    @Synchronized
    fun release() {
        if (snapshot.state != NarrationState.RELEASED) playback.stop()
        snapshot = snapshot.copy(state = NarrationState.RELEASED)
    }

    private fun NarrationSnapshot.error(code: NarrationErrorCode, message: String) =
        copy(state = NarrationState.ERROR, errorCode = code, errorMessage = message)
}

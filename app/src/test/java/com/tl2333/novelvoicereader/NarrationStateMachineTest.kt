package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.narration.NarrationController
import com.tl2333.novelvoicereader.narration.NarrationErrorCode
import com.tl2333.novelvoicereader.narration.NarrationEvent
import com.tl2333.novelvoicereader.narration.NarrationIntent
import com.tl2333.novelvoicereader.narration.NarrationPlaybackPort
import com.tl2333.novelvoicereader.narration.NarrationState
import org.junit.Test

class NarrationStateMachineTest {
    @Test
    fun warmBufferPlaybackPauseAndNativeDeathAreSerialized() {
        val playback = RecordingPlayback()
        val controller = NarrationController(playback)
        assertThat(controller.send(NarrationIntent.Start("doc")).state).isEqualTo(NarrationState.PREPARING)
        assertThat(controller.accept(NarrationEvent.SynthesisStarted("s1")).state).isEqualTo(NarrationState.WARMING_BUFFER)
        controller.accept(NarrationEvent.SynthesisCompleted("s1", 20_000))
        assertThat(controller.accept(NarrationEvent.PlaybackStarted).state).isEqualTo(NarrationState.PLAYING)
        assertThat(controller.send(NarrationIntent.Pause).state).isEqualTo(NarrationState.PAUSED)
        assertThat(controller.accept(NarrationEvent.TtsProcessDied).errorCode).isEqualTo(NarrationErrorCode.TTS_ENGINE_DIED)
        assertThat(playback.stopCount).isEqualTo(1)
    }
}

internal class RecordingPlayback : NarrationPlaybackPort {
    val speeds = mutableListOf<Float>()
    var playCount = 0
    var pauseCount = 0
    var stopCount = 0
    override fun setPlaybackSpeed(speed: Float) { speeds += speed }
    override fun play() { playCount++ }
    override fun pause() { pauseCount++ }
    override fun stop() { stopCount++ }
}

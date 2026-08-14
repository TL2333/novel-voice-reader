package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.narration.NarrationController
import com.tl2333.novelvoicereader.narration.NarrationIntent
import org.junit.Test

class PlaybackSpeedAuthorityTest {
    @Test
    fun speedChangesOnlyReachThePlaybackPortAndPreservePosition() {
        val playback = RecordingPlayback()
        val controller = NarrationController(playback)
        controller.send(NarrationIntent.Start("doc", 9))
        listOf(1f, 1.5f, 2f, 1.25f, 1.75f, 1f).forEach { controller.send(NarrationIntent.SetPlaybackSpeed(it)) }
        assertThat(playback.speeds).containsExactly(1f, 1.5f, 2f, 1.25f, 1.75f, 1f).inOrder()
        assertThat(controller.snapshot.segmentIndex).isEqualTo(9)
        assertThat(controller.snapshot.playbackSpeed).isEqualTo(1f)
        assertThat(playback.stopCount).isEqualTo(0)
    }
}

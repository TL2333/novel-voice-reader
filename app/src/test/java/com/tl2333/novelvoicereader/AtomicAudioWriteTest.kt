package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.tts.cache.WavWriter
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicAudioWriteTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun publishesOnlyTheValidatedDestinationFile() {
        val destination = temporaryFolder.root.resolve("artifact.wav")
        WavWriter.writeMonoPcm16(destination, floatArrayOf(0f, 0.5f, -0.5f), 24_000)
        assertThat(destination.isFile).isTrue()
        assertThat(temporaryFolder.root.listFiles().orEmpty().map { it.name }).containsExactly("artifact.wav")
    }
}

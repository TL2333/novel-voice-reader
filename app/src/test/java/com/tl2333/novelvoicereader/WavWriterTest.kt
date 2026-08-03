package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.tts.cache.WavWriter
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesValidMonoPcm16HeaderAndClipsSamples() {
        val destination = temporaryFolder.root.resolve("test.wav")
        val info = WavWriter.writeMonoPcm16(destination, floatArrayOf(-2f, 0f, 2f), 24_000)
        val bytes = destination.readBytes()

        assertThat(bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII)).isEqualTo("RIFF")
        assertThat(bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)).isEqualTo("WAVE")
        assertThat(bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII)).isEqualTo("data")
        assertThat(info.sizeBytes).isEqualTo(50)
        assertThat(WavWriter.floatToPcm16(floatArrayOf(-2f, 2f)).toList())
            .containsExactly(Short.MIN_VALUE, Short.MAX_VALUE).inOrder()
    }
}

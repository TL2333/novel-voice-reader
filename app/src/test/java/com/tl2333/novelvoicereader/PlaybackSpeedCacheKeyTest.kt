package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.tts.cache.TtsCacheKey
import com.tl2333.novelvoicereader.tts.cache.TtsCacheKeyInput
import com.tl2333.novelvoicereader.tts.tokenizer.NarrationStyle
import org.junit.Test

class PlaybackSpeedCacheKeyTest {
    @Test
    fun cacheInputContainsOnlySynthesisInputs() {
        val fields = TtsCacheKeyInput::class.java.declaredFields.map { it.name }
        assertThat(fields).doesNotContain("playbackSpeed")
        assertThat(fields).doesNotContain("speed")
        val input = TtsCacheKeyInput("model", "sherpa", "文本", 3, NarrationStyle.NEUTRAL, "2")
        assertThat(TtsCacheKey.create(input)).matches("[0-9a-f]{64}")
    }
}

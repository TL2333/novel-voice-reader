package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.tts.cache.TtsCacheKey
import com.tl2333.novelvoicereader.tts.cache.TtsCacheKeyInput
import com.tl2333.novelvoicereader.tts.tokenizer.NarrationStyle
import org.junit.Test

class TtsCacheKeyTest {
    private val base = TtsCacheKeyInput(
        kokoroModelCommit = "155831f1b4ba23b1f5c058be6a61df90cefb2a37",
        sherpaVersion = "1.13.4",
        normalizedText = "夜色渐渐沉了下来。",
        voiceSid = 3,
        speed = 1f,
        style = NarrationStyle.NEUTRAL,
        tokenizerVersion = "1",
    )

    @Test
    fun isDeterministicAndSensitiveToEverySynthesisInput() {
        val first = TtsCacheKey.create(base)
        assertThat(first).matches("[0-9a-f]{64}")
        assertThat(TtsCacheKey.create(base)).isEqualTo(first)
        assertThat(TtsCacheKey.create(base.copy(voiceSid = 58))).isNotEqualTo(first)
        assertThat(TtsCacheKey.create(base.copy(speed = 1.1f))).isNotEqualTo(first)
        assertThat(TtsCacheKey.create(base.copy(style = NarrationStyle.SAD))).isNotEqualTo(first)
        assertThat(TtsCacheKey.create(base.copy(normalizedText = base.normalizedText + "！"))).isNotEqualTo(first)
    }
}

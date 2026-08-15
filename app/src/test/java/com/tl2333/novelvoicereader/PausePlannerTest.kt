package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.content.model.BlockType
import com.tl2333.novelvoicereader.narration.NarrationProfile
import com.tl2333.novelvoicereader.narration.PausePlanner
import org.junit.Test

class PausePlannerTest {
    @Test
    fun centralizesPunctuationAndHeadingPauses() {
        val profile = NarrationProfile()
        assertThat(PausePlanner.pauseAfter("为什么？", BlockType.PARAGRAPH, profile)).isEqualTo(220)
        assertThat(PausePlanner.pauseAfter("第一章", BlockType.HEADING, profile)).isEqualTo(550)
        assertThat(PausePlanner.pauseAfter("继续；", BlockType.PARAGRAPH, profile)).isEqualTo(130)
    }
}

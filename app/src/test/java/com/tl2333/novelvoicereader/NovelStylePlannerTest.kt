package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.tts.tokenizer.NarrationStyle
import com.tl2333.novelvoicereader.tts.tokenizer.NovelStylePlanner
import org.junit.Test

class NovelStylePlannerTest {
    @Test
    fun usesLightweightRulesAndFallsBackAtLowConfidence() {
        assertThat(NovelStylePlanner.plan("她开心地笑道，今天真幸福。").style)
            .isEqualTo(NarrationStyle.HAPPY)
        assertThat(NovelStylePlanner.plan("“住口！你这个混蛋！”").style)
            .isEqualTo(NarrationStyle.ANGRY)
        assertThat(NovelStylePlanner.plan("他走进房间，把书放下。").style)
            .isEqualTo(NarrationStyle.NEUTRAL)
    }

    @Test
    fun explicitStyleOverridesAutomaticRules() {
        val plan = NovelStylePlanner.plan("太好了！！", forcedStyle = NarrationStyle.SAD)
        assertThat(plan.style).isEqualTo(NarrationStyle.SAD)
        assertThat(plan.automatic).isFalse()
        assertThat(plan.parameters.speed).isWithin(0.001f).of(0.88f)
    }
}

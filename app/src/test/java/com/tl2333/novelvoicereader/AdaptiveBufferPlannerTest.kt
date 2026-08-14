package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.narration.AdaptiveBufferPlanner
import com.tl2333.novelvoicereader.narration.DevicePerformanceTier
import com.tl2333.novelvoicereader.narration.SynthesisLoadState
import org.junit.Test

class AdaptiveBufferPlannerTest {
    @Test
    fun highMemoryTargetsNinetyWallSecondsAtEverySupportedSpeed() {
        listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
            val plan = AdaptiveBufferPlanner.plan(DevicePerformanceTier.HIGH_MEMORY, speed, p95Rtf = 0.2)
            assertThat(plan.watermarks.targetWallMs).isEqualTo(90_000)
            assertThat(plan.targetMediaMs).isEqualTo((90_000 * speed).toLong())
            assertThat(AdaptiveBufferPlanner.bufferWallMs(plan.targetMediaMs, speed)).isEqualTo(90_000)
        }
    }

    @Test
    fun riskyAndUnsustainableLoadsIncreasePregeneration() {
        val risk = AdaptiveBufferPlanner.plan(DevicePerformanceTier.HIGH_MEMORY, 1.5f, 0.6)
        assertThat(risk.loadState).isEqualTo(SynthesisLoadState.AT_RISK)
        assertThat(risk.watermarks.targetWallMs).isEqualTo(150_000)
        val impossible = AdaptiveBufferPlanner.plan(DevicePerformanceTier.HIGH_MEMORY, 2f, 0.6)
        assertThat(impossible.aggressivePregeneration).isTrue()
    }
}

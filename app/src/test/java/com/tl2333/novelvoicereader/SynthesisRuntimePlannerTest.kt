package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.narration.DevicePerformanceTier
import com.tl2333.novelvoicereader.narration.SynthesisEpochGuard
import com.tl2333.novelvoicereader.narration.SynthesisRuntimePlanner
import org.junit.Test

class SynthesisRuntimePlannerTest {
    @Test
    fun firstChunkIsSmallAndFollowingChunksStayInRequiredBounds() {
        val plan = SynthesisRuntimePlanner.plan(DevicePerformanceTier.NORMAL_MEMORY, 8, 0.45)
        assertThat(plan.firstChunk.minSegments).isEqualTo(1)
        assertThat(plan.firstChunk.maxSegments).isEqualTo(3)
        assertThat(plan.firstChunk.targetMinDurationMs).isEqualTo(5_000)
        assertThat(plan.firstChunk.targetMaxDurationMs).isEqualTo(10_000)
        assertThat(plan.followingChunks.minSegments).isAtLeast(3)
        assertThat(plan.followingChunks.maxSegments).isAtMost(8)
        assertThat(plan.followingChunks.targetMinDurationMs).isAtLeast(12_000)
        assertThat(plan.followingChunks.targetMaxDurationMs).isAtMost(24_000)
    }

    @Test
    fun benchmarkAndTierChooseThreadsDepthAndConservativeFallback() {
        assertThat(SynthesisRuntimePlanner.plan(DevicePerformanceTier.LOW_MEMORY, 8, 0.9).numThreads).isEqualTo(2)
        assertThat(SynthesisRuntimePlanner.plan(DevicePerformanceTier.NORMAL_MEMORY, 8, 0.9).numThreads).isEqualTo(4)
        assertThat(SynthesisRuntimePlanner.plan(DevicePerformanceTier.HIGH_MEMORY, 8, 0.9).numThreads).isEqualTo(6)
        assertThat(SynthesisRuntimePlanner.plan(DevicePerformanceTier.HIGH_MEMORY, 8, null).numThreads).isEqualTo(4)
        val conservative = SynthesisRuntimePlanner.plan(DevicePerformanceTier.NORMAL_MEMORY, 4, null)
        assertThat(conservative.followingChunks.maxSegments).isEqualTo(6)
    }

    @Test
    fun seekOrStopInvalidatesStaleEpoch() {
        val guard = SynthesisEpochGuard()
        val first = guard.begin()
        assertThat(guard.isCurrent(first)).isTrue()
        val seek = guard.begin()
        assertThat(guard.isCurrent(first)).isFalse()
        assertThat(guard.isCurrent(seek)).isTrue()
        guard.cancel()
        assertThat(guard.isCurrent(seek)).isFalse()
    }
}

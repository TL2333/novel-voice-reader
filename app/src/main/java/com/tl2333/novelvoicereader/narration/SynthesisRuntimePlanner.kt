package com.tl2333.novelvoicereader.narration

import java.util.concurrent.atomic.AtomicLong

data class SynthesisChunkPolicy(
    val minSegments: Int,
    val maxSegments: Int,
    val targetMinDurationMs: Long,
    val targetMaxDurationMs: Long,
)

data class SynthesisRuntimePlan(
    val numThreads: Int,
    val firstChunk: SynthesisChunkPolicy,
    val followingChunks: SynthesisChunkPolicy,
    val maximumQueuedChunksAhead: Int,
)

object SynthesisRuntimePlanner {
    fun plan(tier: DevicePerformanceTier, cpuCores: Int, recentP95Rtf: Double?): SynthesisRuntimePlan {
        require(cpuCores > 0)
        require(recentP95Rtf == null || recentP95Rtf >= 0.0)
        val threads = when (tier) {
            DevicePerformanceTier.LOW_MEMORY -> 2
            DevicePerformanceTier.NORMAL_MEMORY -> 4
            DevicePerformanceTier.HIGH_MEMORY -> if (cpuCores >= 8 && recentP95Rtf != null && recentP95Rtf >= 0.50) 6 else 4
        }
        val conservative = recentP95Rtf == null
        val atRisk = recentP95Rtf != null && recentP95Rtf >= 0.70
        val following = when {
            atRisk -> SynthesisChunkPolicy(3, 5, 16_000, 24_000)
            conservative -> SynthesisChunkPolicy(3, 6, 14_000, 22_000)
            else -> SynthesisChunkPolicy(3, 8, 12_000, 24_000)
        }
        val baseDepth = when (tier) {
            DevicePerformanceTier.LOW_MEMORY -> 3
            DevicePerformanceTier.NORMAL_MEMORY -> 5
            DevicePerformanceTier.HIGH_MEMORY -> 8
        }
        return SynthesisRuntimePlan(
            numThreads = threads,
            firstChunk = SynthesisChunkPolicy(1, 3, 5_000, 10_000),
            followingChunks = following,
            maximumQueuedChunksAhead = (baseDepth + if (atRisk) 2 else 0).coerceAtMost(10),
        )
    }
}

/** Monotonic generation ownership: a seek/stop invalidates all older synthesis completions. */
class SynthesisEpochGuard {
    private val epoch = AtomicLong(0)

    fun begin(): Long = epoch.incrementAndGet()
    fun cancel(): Long = epoch.incrementAndGet()
    fun isCurrent(candidate: Long): Boolean = candidate == epoch.get()
}

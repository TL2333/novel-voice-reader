package com.tl2333.novelvoicereader.narration

import kotlin.math.ceil

enum class DevicePerformanceTier { LOW_MEMORY, NORMAL_MEMORY, HIGH_MEMORY }
enum class SynthesisLoadState { GOOD, SAFE, AT_RISK, UNSUSTAINABLE_REALTIME }

data class RtfStatistics(val p50: Double, val p90: Double, val p95: Double, val sampleCount: Int)

class RtfTracker(private val capacity: Int = 100) {
    private val samples = ArrayDeque<Double>()

    init { require(capacity > 0) }

    @Synchronized
    fun record(generationMs: Long, audioDurationMs: Long) {
        require(generationMs >= 0 && audioDurationMs > 0)
        if (samples.size == capacity) samples.removeFirst()
        samples.addLast(generationMs.toDouble() / audioDurationMs)
    }

    @Synchronized
    fun statistics(): RtfStatistics? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        return RtfStatistics(
            p50 = percentile(sorted, 0.50),
            p90 = percentile(sorted, 0.90),
            p95 = percentile(sorted, 0.95),
            sampleCount = sorted.size,
        )
    }

    private fun percentile(values: List<Double>, percentile: Double): Double =
        values[(ceil(percentile * values.size).toInt() - 1).coerceIn(0, values.lastIndex)]
}

data class BufferWatermarks(val lowWallMs: Long, val targetWallMs: Long, val highWallMs: Long)

data class AdaptiveBufferPlan(
    val loadState: SynthesisLoadState,
    val loadFactor: Double,
    val watermarks: BufferWatermarks,
    val warmStartWallMs: Long,
    val targetMediaMs: Long,
    val highMediaMs: Long,
    val aggressivePregeneration: Boolean,
)

object AdaptiveBufferPlanner {
    private val supportedSpeeds = setOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    fun plan(tier: DevicePerformanceTier, playbackSpeed: Float, p95Rtf: Double?): AdaptiveBufferPlan {
        require(playbackSpeed in supportedSpeeds)
        require(p95Rtf == null || p95Rtf >= 0.0)
        val loadFactor = (p95Rtf ?: defaultRtf(tier)) * playbackSpeed
        val loadState = when {
            loadFactor < 0.60 -> SynthesisLoadState.GOOD
            loadFactor < 0.75 -> SynthesisLoadState.SAFE
            loadFactor < 1.00 -> SynthesisLoadState.AT_RISK
            else -> SynthesisLoadState.UNSUSTAINABLE_REALTIME
        }
        val watermarks = when (loadState) {
            SynthesisLoadState.GOOD, SynthesisLoadState.SAFE -> normalWatermarks(tier)
            SynthesisLoadState.AT_RISK, SynthesisLoadState.UNSUSTAINABLE_REALTIME -> riskWatermarks(tier)
        }
        return AdaptiveBufferPlan(
            loadState = loadState,
            loadFactor = loadFactor,
            watermarks = watermarks,
            warmStartWallMs = when {
                playbackSpeed <= 1.25f -> 5_000
                playbackSpeed <= 1.5f -> 6_500
                else -> 8_000
            },
            targetMediaMs = (watermarks.targetWallMs * playbackSpeed).toLong(),
            highMediaMs = (watermarks.highWallMs * playbackSpeed).toLong(),
            aggressivePregeneration = loadState == SynthesisLoadState.UNSUSTAINABLE_REALTIME,
        )
    }

    fun bufferWallMs(remainingGeneratedMediaDurationMs: Long, playbackSpeed: Float): Long {
        require(remainingGeneratedMediaDurationMs >= 0)
        require(playbackSpeed.isFinite() && playbackSpeed > 0f)
        return (remainingGeneratedMediaDurationMs / playbackSpeed).toLong()
    }

    private fun normalWatermarks(tier: DevicePerformanceTier): BufferWatermarks = when (tier) {
        DevicePerformanceTier.LOW_MEMORY -> BufferWatermarks(10_000, 30_000, 60_000)
        DevicePerformanceTier.NORMAL_MEMORY -> BufferWatermarks(20_000, 60_000, 120_000)
        DevicePerformanceTier.HIGH_MEMORY -> BufferWatermarks(30_000, 90_000, 180_000)
    }

    private fun riskWatermarks(tier: DevicePerformanceTier): BufferWatermarks = when (tier) {
        DevicePerformanceTier.LOW_MEMORY -> BufferWatermarks(20_000, 60_000, 120_000)
        DevicePerformanceTier.NORMAL_MEMORY -> BufferWatermarks(30_000, 100_000, 200_000)
        DevicePerformanceTier.HIGH_MEMORY -> BufferWatermarks(45_000, 150_000, 300_000)
    }

    private fun defaultRtf(tier: DevicePerformanceTier): Double = when (tier) {
        DevicePerformanceTier.LOW_MEMORY -> 0.75
        DevicePerformanceTier.NORMAL_MEMORY -> 0.55
        DevicePerformanceTier.HIGH_MEMORY -> 0.40
    }
}

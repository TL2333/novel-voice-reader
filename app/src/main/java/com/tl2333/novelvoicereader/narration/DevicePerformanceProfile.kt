package com.tl2333.novelvoicereader.narration

import android.app.ActivityManager
import android.content.Context

data class DevicePerformanceProfile(
    val tier: DevicePerformanceTier,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val memoryClassMb: Int,
    val largeMemoryClassMb: Int,
    val recentRtf: RtfStatistics? = null,
)

object DevicePerformanceProfileDetector {
    private const val GIB = 1024L * 1024 * 1024

    fun detect(context: Context): DevicePerformanceProfile {
        val manager = requireNotNull(context.getSystemService(ActivityManager::class.java))
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val tier = when {
            info.totalMem >= 10 * GIB && manager.memoryClass >= 256 -> DevicePerformanceTier.HIGH_MEMORY
            info.totalMem < 4 * GIB || manager.memoryClass < 192 -> DevicePerformanceTier.LOW_MEMORY
            else -> DevicePerformanceTier.NORMAL_MEMORY
        }
        return DevicePerformanceProfile(
            tier = tier,
            totalMemoryBytes = info.totalMem,
            availableMemoryBytes = info.availMem,
            memoryClassMb = manager.memoryClass,
            largeMemoryClassMb = manager.largeMemoryClass,
            recentRtf = DeviceTtsBenchmark.load(context)?.statistics,
        )
    }
}

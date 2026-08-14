package com.tl2333.novelvoicereader.tts.cache

object AudioCacheCapacityPolicy {
    const val MIB: Long = 1024L * 1024
    const val GIB: Long = 1024L * 1024 * 1024
    const val REQUIRED_FREE_RESERVE_BYTES: Long = 4 * GIB

    fun capacityFor(usableBytes: Long): Long {
        require(usableBytes >= 0)
        val tierLimit = when {
            usableBytes >= 32 * GIB -> 4 * GIB
            usableBytes >= 16 * GIB -> 2 * GIB
            usableBytes >= 8 * GIB -> 1 * GIB
            else -> minOf(512 * MIB, usableBytes / 20)
        }
        return minOf(tierLimit, (usableBytes - REQUIRED_FREE_RESERVE_BYTES).coerceAtLeast(0))
    }
}

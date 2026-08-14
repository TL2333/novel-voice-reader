package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.tts.cache.AudioCacheCapacityPolicy
import org.junit.Test

class AudioCacheLruTest {
    @Test
    fun sizesCacheFromFreeSpaceWhileKeepingReserve() {
        val gib = AudioCacheCapacityPolicy.GIB
        assertThat(AudioCacheCapacityPolicy.capacityFor(40 * gib)).isEqualTo(4 * gib)
        assertThat(AudioCacheCapacityPolicy.capacityFor(20 * gib)).isEqualTo(2 * gib)
        assertThat(AudioCacheCapacityPolicy.capacityFor(10 * gib)).isEqualTo(1 * gib)
        assertThat(AudioCacheCapacityPolicy.capacityFor(4 * gib)).isEqualTo(0)
    }
}

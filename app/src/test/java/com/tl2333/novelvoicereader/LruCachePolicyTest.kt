package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.tts.cache.CacheEntry
import com.tl2333.novelvoicereader.tts.cache.LruCachePolicy
import org.junit.Test

class LruCachePolicyTest {
    @Test
    fun evictsOldestWithoutDeletingCurrentPlayback() {
        val entries = listOf(
            CacheEntry("old", "/cache/old.wav", 100, 1),
            CacheEntry("playing", "/cache/playing.wav", 100, 2),
            CacheEntry("new", "/cache/new.wav", 100, 3),
        )

        val result = LruCachePolicy.selectEvictions(
            entries,
            maxBytes = 100,
            protectedPaths = setOf("/cache/playing.wav"),
        )

        assertThat(result.map { it.key }).containsExactly("old", "new").inOrder()
    }

    @Test
    fun returnsNothingWhenAtLimit() {
        assertThat(
            LruCachePolicy.selectEvictions(listOf(CacheEntry("a", "a.wav", 5, 1)), 5),
        ).isEmpty()
    }
}

package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.tts.cache.TtsAudioCache
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TtsAudioCacheClearTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun clearBookDefersProtectedFileAndLeavesOtherBookCache() {
        val root = temporaryFolder.newFolder("narration")
        val cache = TtsAudioCache(root, "book-a", maxBytes = Long.MAX_VALUE)
        val playing = cache.put(KEY_A, shortArrayOf(1, 2), 24_000)
        val stale = cache.put(KEY_B, shortArrayOf(3, 4), 24_000)
        val otherBook = TtsAudioCache(root, "book-b", maxBytes = Long.MAX_VALUE)
            .put(KEY_C, shortArrayOf(5, 6), 24_000)

        cache.protect(playing)
        try {
            val result = TtsAudioCache.clearBook(root, "book-a")

            assertThat(result.successful).isTrue()
            assertThat(result.retainedProtectedFiles).containsExactly(playing.absoluteFile)
            assertThat(playing.exists()).isTrue()
            assertThat(stale.exists()).isFalse()
            assertThat(otherBook.exists()).isTrue()
        } finally {
            cache.unprotect(playing)
        }

        assertThat(playing.exists()).isFalse()
        assertThat(otherBook.exists()).isTrue()
    }

    @Test
    fun clearAllWaitsForFinalPlaybackReferenceAndStaysInsideCacheRoot() {
        val root = temporaryFolder.newFolder("all-narration")
        val unrelatedEpub = File(temporaryFolder.newFolder("books"), "novel.epub")
            .apply { writeText("not cache data") }
        val cache = TtsAudioCache(root, "book-a", maxBytes = Long.MAX_VALUE)
        val playing = cache.put(KEY_A, shortArrayOf(1, 2), 24_000)
        val stale = cache.put(KEY_B, shortArrayOf(3, 4), 24_000)

        cache.protect(playing)
        cache.protect(playing)
        val result = TtsAudioCache.clearAll(root)

        assertThat(result.successful).isTrue()
        assertThat(playing.exists()).isTrue()
        assertThat(stale.exists()).isFalse()
        assertThat(unrelatedEpub.exists()).isTrue()

        cache.unprotect(playing)
        assertThat(playing.exists()).isTrue()
        cache.unprotect(playing)
        assertThat(playing.exists()).isFalse()
        assertThat(unrelatedEpub.exists()).isTrue()
    }

    private companion object {
        val KEY_A = "a".repeat(64)
        val KEY_B = "b".repeat(64)
        val KEY_C = "c".repeat(64)
    }
}

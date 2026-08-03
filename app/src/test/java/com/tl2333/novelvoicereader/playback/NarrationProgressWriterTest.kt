package com.tl2333.novelvoicereader.playback

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test

class NarrationProgressWriterTest {
    @Test
    fun coalescesUpdatesAndSkipsAnExactDuplicate() {
        runBlocking {
            val writes = mutableListOf<NarrationProgressSnapshot>()
            val writer = writerRecordingInto(writes)
            val first = snapshot("chapter.xhtml", 0.1)
            val latest = snapshot("chapter.xhtml", 0.3)

            writer.offer(first)
            writer.offer(latest)
            writer.offer(latest)

            assertThat(writer.flushLatest()).isTrue()
            assertThat(writer.flushLatest()).isFalse()
            assertThat(writes).containsExactly(latest)
        }
    }

    @Test
    fun returningToLastSavedLocatorCancelsAnOlderPendingUpdate() {
        runBlocking {
            val writes = mutableListOf<NarrationProgressSnapshot>()
            val writer = writerRecordingInto(writes)
            val first = snapshot("one.xhtml", 0.1)

            writer.offer(first)
            writer.flushLatest()
            writer.offer(snapshot("two.xhtml", 0.2))
            writer.offer(first)

            assertThat(writer.flushLatest()).isFalse()
            assertThat(writes).containsExactly(first)
        }
    }

    @Test
    fun retainsNewestLocationOfferedDuringAWrite() {
        runBlocking {
            val writeStarted = CompletableDeferred<Unit>()
            val allowWrite = CompletableDeferred<Unit>()
            val writes = mutableListOf<NarrationProgressSnapshot>()
            val writer = NarrationProgressWriter("book-id") { _, json, progression ->
                writeStarted.complete(Unit)
                allowWrite.await()
                writes += NarrationProgressSnapshot(json, progression)
            }
            val first = snapshot("one.xhtml", 0.1)
            val latest = snapshot("two.xhtml", 0.2)

            writer.offer(first)
            val firstFlush = async { writer.flushLatest() }
            writeStarted.await()
            writer.offer(latest)
            allowWrite.complete(Unit)
            firstFlush.await()
            writer.flushLatest()

            assertThat(writes).containsExactly(first, latest).inOrder()
        }
    }

    private fun writerRecordingInto(
        writes: MutableList<NarrationProgressSnapshot>,
    ): NarrationProgressWriter = NarrationProgressWriter("book-id") { _, json, progression ->
        writes += NarrationProgressSnapshot(json, progression)
    }

    private fun snapshot(href: String, progression: Double): NarrationProgressSnapshot =
        NarrationProgressSnapshot(
            locatorJson = """{"href":"$href","locations":{"totalProgression":$progression}}""",
            totalProgression = progression,
        )
}

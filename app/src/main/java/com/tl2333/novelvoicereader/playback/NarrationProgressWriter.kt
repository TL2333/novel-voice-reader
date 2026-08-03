package com.tl2333.novelvoicereader.playback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.r2.shared.publication.Locator

/** A complete Readium location waiting to be committed as a book's durable progress. */
internal data class NarrationProgressSnapshot(
    val locatorJson: String,
    val totalProgression: Double?,
) {
    companion object {
        fun from(locator: Locator): NarrationProgressSnapshot = NarrationProgressSnapshot(
            locatorJson = locator.toJSON().toString(),
            totalProgression = locator.locations.totalProgression,
        )
    }
}

/**
 * Coalesces narration locations before they reach Room.
 *
 * [offer] is intentionally cheap and non-suspending so every TTS locator can be captured. The
 * service periodically calls [flushLatest], then calls it once more during shutdown. Exact
 * duplicate locators are ignored, and an update arriving during a database write remains pending
 * for the next flush.
 */
internal class NarrationProgressWriter(
    private val bookId: String,
    private val save: suspend (
        bookId: String,
        locatorJson: String,
        totalProgression: Double?,
    ) -> Unit,
) {
    private val stateLock = Any()
    private val writeMutex = Mutex()

    private var pending: NarrationProgressSnapshot? = null
    private var inFlightJson: String? = null
    private var lastSavedJson: String? = null

    fun offer(locator: Locator): Boolean = offer(NarrationProgressSnapshot.from(locator))

    internal fun offer(snapshot: NarrationProgressSnapshot): Boolean = synchronized(stateLock) {
        when (snapshot.locatorJson) {
            pending?.locatorJson -> false
            inFlightJson -> {
                // This exact location is already being written. It is also the newest event, so
                // discard any superseded pending location.
                pending = null
                false
            }

            lastSavedJson -> if (inFlightJson == null) {
                // The navigator returned to the last committed location, so an older pending
                // location must not be written afterward.
                pending = null
                false
            } else {
                // A different write is in flight. Queue the return to the previously saved
                // location so it can become authoritative afterward.
                pending = snapshot
                true
            }

            else -> {
                pending = snapshot
                true
            }
        }
    }

    suspend fun flushLatest(): Boolean = writeMutex.withLock {
        val snapshot = synchronized(stateLock) {
            pending.also {
                pending = null
                inFlightJson = it?.locatorJson
            }
        } ?: return@withLock false

        try {
            save(bookId, snapshot.locatorJson, snapshot.totalProgression)
        } catch (error: Exception) {
            synchronized(stateLock) {
                // Preserve the failed snapshot only if no newer locator arrived while saving.
                if (pending == null) pending = snapshot
                inFlightJson = null
            }
            throw error
        }

        synchronized(stateLock) {
            lastSavedJson = snapshot.locatorJson
            inFlightJson = null
            if (pending?.locatorJson == snapshot.locatorJson) pending = null
        }
        true
    }
}

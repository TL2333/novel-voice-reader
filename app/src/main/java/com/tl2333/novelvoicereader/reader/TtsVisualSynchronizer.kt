@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.tl2333.novelvoicereader.reader

import android.graphics.Color
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/** Keeps a standalone Readium TTS navigator synchronized with the visible EPUB navigator. */
class TtsVisualSynchronizer(
    private val scope: CoroutineScope,
    private val bookId: String,
    private val publication: Publication,
    private val visualNavigator: VisualNavigator,
    private val narrationFactory: ReaderNarrationFactory,
    private val narrationSession: ReaderNarrationSession,
    private val automaticFollow: () -> Boolean = { true },
    private val onNarrationLocator: (Locator) -> Unit = {},
) {
    data class State(
        val available: Boolean = true,
        val preparing: Boolean = false,
        val playing: Boolean = false,
        val utterance: String? = null,
        val error: String? = null,
    )

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(State())
    private var navigator: ReaderNarrationNavigator? = null
    private var observerJobs: List<Job> = emptyList()

    val state: StateFlow<State> = mutableState.asStateFlow()

    fun start() {
        scope.launch {
            mutex.withLock {
                val existing = navigator
                if (existing != null) {
                    existing.play()
                    return@withLock
                }

                mutableState.value = State(preparing = true)
                val startLocator = try {
                    visualNavigator.firstVisibleElementLocator()
                } catch (error: Exception) {
                    mutableState.value = State(
                        error = "Could not determine the visible reading position: ${error.message}",
                    )
                    return@withLock
                }

                val created = narrationFactory.create(
                    bookId = bookId,
                    publication = publication,
                    initialLocator = startLocator,
                    onStopRequested = ::stop,
                )
                val createdNavigator = when (created) {
                    is NarrationCreationResult.Success -> created.navigator
                    is NarrationCreationResult.Failure -> {
                        mutableState.value = State(error = created.message)
                        return@withLock
                    }
                }

                try {
                    narrationSession.open(bookId, createdNavigator)
                } catch (error: Exception) {
                    createdNavigator.close()
                    mutableState.value = State(
                        error = "Could not open the narration media session: ${error.message}",
                    )
                    return@withLock
                }

                navigator = createdNavigator
                observe(createdNavigator)
                createdNavigator.play()
            }
        }
    }

    fun play() {
        navigator?.play() ?: start()
    }

    fun pause() {
        navigator?.pause()
    }

    fun previous() {
        navigator?.skipToPreviousUtterance()
    }

    fun next() {
        navigator?.skipToNextUtterance()
    }

    fun stop() {
        scope.launch { stopNow() }
    }

    suspend fun close() {
        stopNow()
    }

    private fun observe(navigator: ReaderNarrationNavigator) {
        observerJobs.forEach(Job::cancel)

        val playbackJob = navigator.playback
            .onEach { playback ->
                when (val playbackState = playback.state) {
                    TtsNavigator.State.Ready -> {
                        mutableState.value = mutableState.value.copy(
                            preparing = false,
                            playing = playback.playWhenReady,
                            error = null,
                        )
                    }

                    TtsNavigator.State.Ended -> stop()
                    is TtsNavigator.State.Failure -> {
                        mutableState.value = mutableState.value.copy(
                            preparing = false,
                            playing = false,
                            error = playbackState.error.message,
                        )
                    }

                    else -> {
                        // TTS 3.2.0 currently exposes only Ready/Ended/Failure, while the common
                        // MediaNavigator state type also permits buffering implementations.
                        mutableState.value = mutableState.value.copy(
                            preparing = true,
                            playing = playback.playWhenReady,
                        )
                    }
                }
            }
            .launchIn(scope)

        val decorationJob = (visualNavigator as? DecorableNavigator)?.let { decorable ->
            navigator.location
                .onEach { location ->
                    val locator = location.utteranceLocator
                    mutableState.value = mutableState.value.copy(
                        utterance = location.utterance,
                    )
                    decorable.applyDecorations(
                        decorations = listOf(
                            Decoration(
                                id = CURRENT_UTTERANCE_DECORATION_ID,
                                locator = locator,
                                style = Decoration.Style.Highlight(
                                    tint = Color.argb(96, 244, 67, 54),
                                ),
                            ),
                        ),
                        group = TTS_DECORATION_GROUP,
                    )
                }
                .launchIn(scope)
        }

        val followJob = navigator.location
            // Kokoro provides sentence audio without trustworthy word timings. Following only the
            // utterance locator avoids presenting pseudo word-level synchronization.
            .map { it.utteranceLocator }
            .distinctUntilChanged()
            .throttleLatest(AUTO_FOLLOW_PERIOD)
            .onEach { locator ->
                if (automaticFollow()) visualNavigator.go(locator, animated = false)
            }
            .launchIn(scope)

        // This callback is independent of visual following: background narration and narration
        // with automatic follow disabled must still advance the durable reading position.
        val progressJob = navigator.currentLocator
            .onEach(onNarrationLocator)
            .launchIn(scope)

        observerJobs = listOfNotNull(playbackJob, decorationJob, followJob, progressJob)
    }

    private suspend fun stopNow() = mutex.withLock {
        observerJobs.forEach(Job::cancel)
        observerJobs = emptyList()
        navigator = null

        try {
            narrationSession.close()
        } finally {
            (visualNavigator as? DecorableNavigator)?.applyDecorations(
                decorations = emptyList(),
                group = TTS_DECORATION_GROUP,
            )
            mutableState.value = State()
        }
    }

    private fun <T> Flow<T>.throttleLatest(period: Duration): Flow<T> = flow {
        conflate().collect { value ->
            emit(value)
            delay(period)
        }
    }

    private companion object {
        const val CURRENT_UTTERANCE_DECORATION_ID = "tts-current-utterance"
        const val TTS_DECORATION_GROUP = "tts-current-utterance"
        val AUTO_FOLLOW_PERIOD: Duration = 1.seconds
    }
}

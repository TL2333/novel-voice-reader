@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.tl2333.novelvoicereader.reader

import android.app.Application
import androidx.media3.common.Player
import com.tl2333.novelvoicereader.data.database.BookRepository
import com.tl2333.novelvoicereader.data.database.BookmarkRepository
import com.tl2333.novelvoicereader.data.database.ReadingProgressRepository
import com.tl2333.novelvoicereader.data.preferences.ReaderTtsPreferencesStore
import kotlinx.coroutines.flow.StateFlow
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.r2.navigator.preferences.PreferencesEditor
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.tokenizer.TextTokenizer

/** Application-level dependencies required by the reader feature. */
data class ReaderDependencies(
    val publicationManager: PublicationManager,
    val preferencesStore: ReaderTtsPreferencesStore,
    val bookRepository: BookRepository? = null,
    val readingProgressRepository: ReadingProgressRepository? = null,
    val bookmarkRepository: BookmarkRepository? = null,
    val narrationFactory: ReaderNarrationFactory? = null,
    val narrationSessionFactory: (Application) -> ReaderNarrationSession = {
        InProcessReaderNarrationSession()
    },
) {
    companion object {
        fun fallback(application: Application): ReaderDependencies = ReaderDependencies(
            publicationManager = PublicationManager(application),
            preferencesStore = ReaderTtsPreferencesStore(application),
        )
    }
}

/** Implemented by [Application] (or delegated to its AppContainer) for compile-time wiring. */
interface ReaderDependenciesOwner {
    val readerDependencies: ReaderDependencies
}

/** Type-erased, but still compile-time-safe, surface around a concrete generic Readium navigator. */
interface ReaderNarrationNavigator {
    val currentLocator: StateFlow<Locator>
    val location: StateFlow<TtsNavigator.Location>
    val playback: StateFlow<TtsNavigator.Playback>

    fun play()
    fun pause()
    fun skipToPreviousUtterance()
    fun skipToNextUtterance()
    fun asMedia3Player(): Player
    fun close()
}

sealed interface NarrationCreationResult {
    data class Success(val navigator: ReaderNarrationNavigator) : NarrationCreationResult
    data class Failure(val message: String, val cause: Throwable? = null) : NarrationCreationResult
}

fun interface ReaderNarrationFactory {
    suspend fun create(
        bookId: String,
        publication: Publication,
        initialLocator: Locator?,
        onStopRequested: () -> Unit,
    ): NarrationCreationResult
}

/**
 * Exact Readium 3.2.0 bridge used by AppContainer to plug in a concrete Kokoro provider.
 *
 * This keeps the reader independent of Kokoro's constructor while preserving all generic type
 * checks. A fresh provider is requested for each database `bookId`, preventing cache ownership
 * from being inferred from non-unique publication metadata or mutable global state.
 */
class ReadiumTtsNarrationFactory<
    S : TtsEngine.Settings,
    P : TtsEngine.Preferences<P>,
    PE : PreferencesEditor<P>,
    F : TtsEngine.Error,
    V : TtsEngine.Voice,
    >(
    private val application: Application,
    private val ttsEngineProviderFactory: (bookId: String) -> TtsEngineProvider<S, P, PE, F, V>,
    private val initialPreferences: suspend (Publication) -> P? = { null },
    private val tokenizerFactory: (Language?) -> TextTokenizer = {
        ChineseReadiumTextTokenizer()
    },
) : ReaderNarrationFactory {
    override suspend fun create(
        bookId: String,
        publication: Publication,
        initialLocator: Locator?,
        onStopRequested: () -> Unit,
    ): NarrationCreationResult {
        val ttsEngineProvider = ttsEngineProviderFactory(bookId)
        val factory = TtsNavigatorFactory(
            application = application,
            publication = publication,
            ttsEngineProvider = ttsEngineProvider,
            tokenizerFactory = tokenizerFactory,
        ) ?: return NarrationCreationResult.Failure(
            if (publication.metadata.layout == Layout.FIXED) {
                "此固定版式 EPUB 可以阅读，但没有可供朗读的文本。"
            } else {
                "此 EPUB 没有 Readium 可朗读文本服务。"
            },
        )

        val listener = object : TtsNavigator.Listener {
            override fun onStopRequested() = onStopRequested()
        }

        return when (
            val result = factory.createNavigator(
                listener = listener,
                initialLocator = initialLocator,
                initialPreferences = initialPreferences(publication),
            )
        ) {
            is Try.Success -> NarrationCreationResult.Success(
                ReadiumNarrationNavigator(result.value),
            )

            is Try.Failure -> NarrationCreationResult.Failure(result.value.message)
        }
    }
}

private class ReadiumNarrationNavigator<
    S : TtsEngine.Settings,
    P : TtsEngine.Preferences<P>,
    F : TtsEngine.Error,
    V : TtsEngine.Voice,
    >(
    private val delegate: TtsNavigator<S, P, F, V>,
) : ReaderNarrationNavigator {
    override val currentLocator: StateFlow<Locator> = delegate.currentLocator
    override val location: StateFlow<TtsNavigator.Location> = delegate.location
    override val playback: StateFlow<TtsNavigator.Playback> = delegate.playback

    override fun play() = delegate.play()
    override fun pause() = delegate.pause()
    override fun skipToPreviousUtterance() = delegate.skipToPreviousUtterance()
    override fun skipToNextUtterance() = delegate.skipToNextUtterance()
    override fun asMedia3Player(): Player = delegate.asMedia3Player()
    override fun close() = delegate.close()
}

/** Owns a narration navigator while optionally placing it in a MediaSessionService. */
interface ReaderNarrationSession {
    suspend fun open(bookId: String, navigator: ReaderNarrationNavigator)
    suspend fun close()
}

/** Safe fallback used when an app container has not opted into background media playback yet. */
class InProcessReaderNarrationSession : ReaderNarrationSession {
    private var navigator: ReaderNarrationNavigator? = null

    override suspend fun open(bookId: String, navigator: ReaderNarrationNavigator) {
        this.navigator?.close()
        this.navigator = navigator
    }

    override suspend fun close() {
        navigator?.close()
        navigator = null
    }
}

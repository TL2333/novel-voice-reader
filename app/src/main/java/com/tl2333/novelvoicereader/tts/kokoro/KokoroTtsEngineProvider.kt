@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.tl2333.novelvoicereader.tts.kokoro

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import com.k2fsa.sherpa.onnx.OfflineTts
import com.tl2333.novelvoicereader.tts.cache.TtsAudioCache
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.ThrowableError
import org.readium.r2.shared.util.Try

class KokoroTtsEngineProvider(
    context: Context,
    private val defaults: KokoroTtsDefaults = KokoroTtsDefaults(),
    val modelLocator: KokoroModelLocator = KokoroModelLocator(context),
    private val cacheMaxBytes: suspend () -> Long = { TtsAudioCache.DEFAULT_MAX_BYTES },
    private val bookIdResolver: (Publication) -> String = { publication ->
        publication.metadata.identifier
            ?: publication.metadata.title
            ?: "unidentified-publication"
    },
    private val fixedBookId: String? = null,
    private val nativeSessionMutex: Mutex = Mutex(),
) : TtsEngineProvider<
    KokoroTtsSettings,
    KokoroTtsPreferences,
    KokoroTtsPreferencesEditor,
    KokoroTtsError,
    KokoroTtsVoice
    > {
    private val applicationContext = context.applicationContext
    private val narrationCacheRoot = File(applicationContext.cacheDir, "narration")

    init {
        require(fixedBookId == null || fixedBookId.isNotBlank())
    }

    /** Returns an immutable book-scoped provider; no mutable current-book global is used. */
    fun forBook(bookId: String): KokoroTtsEngineProvider {
        require(bookId.isNotBlank())
        return KokoroTtsEngineProvider(
            context = applicationContext,
            defaults = defaults,
            modelLocator = modelLocator,
            cacheMaxBytes = cacheMaxBytes,
            bookIdResolver = bookIdResolver,
            fixedBookId = bookId,
            nativeSessionMutex = nativeSessionMutex,
        )
    }

    override suspend fun createEngine(
        publication: Publication,
        initialPreferences: KokoroTtsPreferences,
    ): Try<KokoroTtsEngine, Error> =
        createEngine(
            metadata = publication.metadata,
            initialPreferences = initialPreferences,
            audioCache = TtsAudioCache(
                cacheRoot = narrationCacheRoot,
                bookId = fixedBookId ?: bookIdResolver(publication),
                maxBytes = cacheMaxBytes().coerceAtLeast(0L),
            ),
        )

    /** Creates the retained engine used by diagnostics before an EPUB is opened. */
    suspend fun createDiagnosticEngine(
        initialPreferences: KokoroTtsPreferences = KokoroTtsPreferences(),
    ): Try<KokoroTtsEngine, Error> =
        createEngine(Metadata(languages = listOf("zh-Hans")), initialPreferences, audioCache = null)

    private suspend fun createEngine(
        metadata: Metadata,
        initialPreferences: KokoroTtsPreferences,
        audioCache: TtsAudioCache?,
    ): Try<KokoroTtsEngine, Error> = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        var offlineTts: OfflineTts? = null
        val nativeLeaseHeld = AtomicBoolean(false)
        try {
            nativeSessionMutex.lock()
            nativeLeaseHeld.set(true)
            val runtimeModel = modelLocator.prepareRuntimeData()
            val config = KokoroConfigFactory.create(runtimeModel.espeakDataDirectory)
            val createdTts = OfflineTts(applicationContext.assets, config)
            offlineTts = createdTts

            val sampleRate = createdTts.sampleRate()
            val speakerCount = createdTts.numSpeakers()
            check(sampleRate > 0) { "sherpa-onnx returned invalid sample rate $sampleRate" }
            check(speakerCount > KokoroVoiceCatalog.voices.last().sid) {
                "Kokoro model exposes $speakerCount speakers; sid ${KokoroVoiceCatalog.voices.last().sid} is required"
            }

            Try.success(
                KokoroTtsEngine(
                    offlineTts = createdTts,
                    settingsResolver = KokoroTtsSettingsResolver(metadata, defaults),
                    initialPreferences = initialPreferences,
                    runtimeModel = runtimeModel,
                    initializationDurationMillis = nanosToMillis(
                        SystemClock.elapsedRealtimeNanos() - startedAt,
                    ),
                    audioCache = audioCache,
                    onReleased = {
                        if (nativeLeaseHeld.compareAndSet(true, false)) {
                            nativeSessionMutex.unlock()
                        }
                    },
                ),
            )
        } catch (error: CancellationException) {
            runCatching { offlineTts?.release() }
            if (nativeLeaseHeld.compareAndSet(true, false)) nativeSessionMutex.unlock()
            throw error
        } catch (error: Throwable) {
            runCatching { offlineTts?.release() }
            if (nativeLeaseHeld.compareAndSet(true, false)) nativeSessionMutex.unlock()
            Try.failure(mapInitializationError(error))
        }
    }

    override fun createPreferencesEditor(
        publication: Publication,
        initialPreferences: KokoroTtsPreferences,
    ): KokoroTtsPreferencesEditor =
        KokoroTtsPreferencesEditor(initialPreferences, publication.metadata, defaults)

    override fun createEmptyPreferences(): KokoroTtsPreferences = KokoroTtsPreferences()

    override fun getPlaybackParameters(settings: KokoroTtsSettings): PlaybackParameters =
        PlaybackParameters(settings.effectiveSpeed.toFloat(), 1f)

    override fun updatePlaybackParameters(
        previousPreferences: KokoroTtsPreferences,
        playbackParameters: PlaybackParameters,
    ): KokoroTtsPreferences = previousPreferences.copy(
        speed = playbackParameters.speed.toDouble().coerceIn(
            KokoroTtsPreferences.MIN_SPEED,
            KokoroTtsPreferences.MAX_SPEED,
        ),
    )

    @androidx.media3.common.util.UnstableApi
    override fun mapEngineError(error: KokoroTtsError): PlaybackException =
        PlaybackException(
            "Kokoro TTS ${error.code}: ${error.message}",
            (error.cause as? ThrowableError<*>)?.throwable,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        )

    private fun mapInitializationError(error: Throwable): KokoroTtsError = when {
        error is KokoroModelPreparationException -> when (error.failure) {
            KokoroModelFailure.MISSING -> KokoroTtsError.fromThrowable(
                KokoroTtsErrorCode.MODEL_MISSING,
                "A required packaged Kokoro model file is missing: ${error.message}",
                error,
            )

            KokoroModelFailure.CORRUPT -> KokoroTtsError.fromThrowable(
                KokoroTtsErrorCode.MODEL_CORRUPT,
                "Packaged Kokoro model verification failed: ${error.message}",
                error,
            )

            KokoroModelFailure.STORAGE -> KokoroTtsError.fromThrowable(
                KokoroTtsErrorCode.ENGINE_INIT_FAILED,
                "Kokoro runtime data could not be prepared in app storage: ${error.message}",
                error,
            )
        }

        error.hasUnsatisfiedLinkCause() -> KokoroTtsError.fromThrowable(
            KokoroTtsErrorCode.NATIVE_LIBRARY_MISSING,
            "The sherpa-onnx ARM64 native library could not be loaded on this device.",
            error,
        )

        error is OutOfMemoryError -> KokoroTtsError.fromThrowable(
            KokoroTtsErrorCode.ENGINE_INIT_FAILED,
            "Kokoro initialization ran out of memory on this device.",
            error,
        )

        else -> KokoroTtsError.fromThrowable(
            KokoroTtsErrorCode.ENGINE_INIT_FAILED,
            "Kokoro initialization failed: ${error.message ?: error.javaClass.simpleName}",
            error,
        )
    }

    private fun Throwable.hasUnsatisfiedLinkCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is UnsatisfiedLinkError || current is NoClassDefFoundError) return true
            current = current.cause
        }
        return false
    }

    private fun nanosToMillis(value: Long): Long = value / 1_000_000L
}

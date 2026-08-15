@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.tl2333.novelvoicereader.tts.kokoro

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.PlaybackParams
import android.media.AudioTrack
import com.tl2333.novelvoicereader.BuildConfig
import com.tl2333.novelvoicereader.tts.cache.TtsAudioCache
import com.tl2333.novelvoicereader.tts.cache.TtsCacheKey
import com.tl2333.novelvoicereader.tts.cache.TtsCacheKeyInput
import com.tl2333.novelvoicereader.tts.cache.WavInfo
import com.tl2333.novelvoicereader.tts.cache.WavWriter
import com.tl2333.novelvoicereader.tts.tokenizer.ChineseSentenceTokenizer
import com.tl2333.novelvoicereader.tts.tokenizer.ChineseTextNormalizer
import com.tl2333.novelvoicereader.tts.tokenizer.EnglishSentenceTokenizer
import com.tl2333.novelvoicereader.narration.EnglishTextNormalizer
import com.tl2333.novelvoicereader.narration.LanguageDetector
import com.tl2333.novelvoicereader.narration.SpeechLanguage
import com.tl2333.novelvoicereader.tts.tokenizer.NarrationStyle
import com.tl2333.novelvoicereader.tts.tokenizer.NovelStylePlanner
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import org.readium.navigator.media.tts.TtsEngine
import org.readium.r2.shared.util.Error as ReadiumError
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.ThrowableError

enum class KokoroTtsErrorCode {
    MODEL_MISSING,
    MODEL_CORRUPT,
    NATIVE_LIBRARY_MISSING,
    ENGINE_INIT_FAILED,
    EMPTY_TEXT,
    EMPTY_GENERATED_AUDIO,
    TEXT_TOO_LONG,
    SYNTHESIS_FAILED,
    AUDIO_PLAYBACK_FAILED,
    CANCELLED,
    UNSUPPORTED_LANGUAGE,
}

enum class KokoroEngineState {
    INITIALIZING,
    READY,
    SYNTHESIZING,
    STOPPING,
    RELEASED,
    FAILED,
}

data class KokoroTtsError(
    val code: KokoroTtsErrorCode,
    override val message: String,
    override val cause: ReadiumError? = null,
) : TtsEngine.Error {
    companion object {
        fun fromThrowable(
            code: KokoroTtsErrorCode,
            message: String,
            throwable: Throwable,
        ): KokoroTtsError = KokoroTtsError(code, message, ThrowableError(throwable))

        fun cancelled(message: String = "Kokoro operation was cancelled."): KokoroTtsError =
            KokoroTtsError(KokoroTtsErrorCode.CANCELLED, message)
    }
}

class KokoroTtsOperationException(
    val error: KokoroTtsError,
) : Exception(error.message, (error.cause as? ThrowableError<*>)?.throwable)

data class KokoroTtsVoice(
    val sid: Int,
    val internalName: String,
    val displayName: String,
    val gender: VoiceGender,
    override val language: Language = Language("zh-Hans"),
) : TtsEngine.Voice

data class KokoroDiagnosticAudio(
    val wav: WavInfo,
    val synthesisDurationMillis: Long,
    val voiceSid: Int,
    val speed: Double,
    val style: NarrationStyle,
)

/**
 * sherpa-onnx-backed Readium engine.
 *
 * The single worker owns playback and delegates callback-free native inference to the isolated
 * TTS-process [synthesisBackend]. Cancellation marks the in-flight request so a returned artifact
 * can be discarded without corrupting the playback queue.
 */
class KokoroTtsEngine internal constructor(
    private val synthesisBackend: KokoroSynthesisBackend,
    private val settingsResolver: KokoroTtsSettingsResolver,
    initialPreferences: KokoroTtsPreferences,
    val runtimeModel: KokoroRuntimeModel,
    val initializationDurationMillis: Long,
    private val audioCache: TtsAudioCache? = null,
    private val onReleased: () -> Unit = {},
) : TtsEngine<
    KokoroTtsSettings,
    KokoroTtsPreferences,
    KokoroTtsError,
    KokoroTtsVoice
    > {
    override val voices: Set<KokoroTtsVoice> = KokoroVoiceCatalog.voices
        .mapTo(linkedSetOf()) { voice ->
            KokoroTtsVoice(
                sid = voice.sid,
                internalName = voice.internalName,
                displayName = voice.displayName,
                gender = voice.gender,
            )
        }

    private val _settings = MutableStateFlow(settingsResolver.settings(initialPreferences))
    override val settings: StateFlow<KokoroTtsSettings> = _settings.asStateFlow()

    private val _engineState = MutableStateFlow(KokoroEngineState.INITIALIZING)
    val engineState: StateFlow<KokoroEngineState> = _engineState.asStateFlow()

    private val lifecycleLock = Any()
    private val pauseMonitor = java.lang.Object()
    private val cancellationEpoch = AtomicLong(0L)
    private val workQueue = Channel<Work>(Channel.UNLIMITED)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kokoro-offline-tts").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val workerScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var listener: TtsEngine.Listener<KokoroTtsError>? = null

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    @Volatile
    private var isPaused: Boolean = false

    @Volatile
    private var isClosed: Boolean = false

    private var activeWork: Work? = null

    init {
        _engineState.value = KokoroEngineState.READY
        workerScope.launch { consumeWork() }
    }

    override fun submitPreferences(preferences: KokoroTtsPreferences) {
        check(!isClosed) { "Engine is closed." }
        val updated = settingsResolver.settings(preferences)
        _settings.value = updated
        activeAudioTrack?.let { track -> runCatching { applyPlaybackSpeed(track, updated.effectiveSpeed) } }
    }

    override fun setListener(listener: TtsEngine.Listener<KokoroTtsError>?) {
        this.listener = listener
    }

    override fun speak(
        requestId: TtsEngine.RequestId,
        text: String,
        language: Language?,
    ) {
        val work = synchronized(lifecycleLock) {
            check(!isClosed) { "Engine is closed." }
            Work.Speak(requestId, text, language, cancellationEpoch.get())
        }
        if (workQueue.trySend(work).isFailure) {
            notifyError(
                requestId,
                KokoroTtsError.cancelled("Kokoro engine closed before the utterance was queued."),
            )
        }
    }

    /** Pauses active AudioTrack playback until [resume] or [stop]. */
    fun pause() {
        if (isClosed) return
        synchronized(pauseMonitor) {
            isPaused = true
            runCatching { activeAudioTrack?.pause() }
        }
    }

    /** Resumes active AudioTrack playback. */
    fun resume() {
        if (isClosed) return
        synchronized(pauseMonitor) {
            isPaused = false
            activeAudioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED) runCatching { track.play() }
            }
            pauseMonitor.notifyAll()
        }
    }

    override fun stop() {
        cancelCurrentAndFlushQueue(markClosed = false)
    }

    override fun close() {
        val shouldClose = synchronized(lifecycleLock) {
            if (isClosed) false else {
                isClosed = true
                true
            }
        }
        if (!shouldClose) return

        cancelCurrentAndFlushQueue(markClosed = true)
        workQueue.close()
    }

    /**
     * Generates a real PCM16 WAV using this engine's existing OfflineTts instance.
     * The request is serialized with normal Readium utterances on the same worker.
     */
    suspend fun synthesizeDiagnostic(
        destination: File,
        text: String,
        voiceSid: Int = settings.value.effectiveVoiceSid,
        speed: Double = settings.value.effectiveSpeed,
        style: NarrationStyle = settings.value.effectiveStyle,
        autoStyle: Boolean = false,
        playAfterSynthesis: Boolean = false,
    ): KokoroDiagnosticAudio {
        require(text.isNotBlank()) { "Diagnostic text must not be blank." }
        require(KokoroVoiceCatalog.bySid(voiceSid) != null) { "Unknown Kokoro voice sid: $voiceSid" }
        require(speed in KokoroTtsPreferences.MIN_SPEED..KokoroTtsPreferences.MAX_SPEED) {
            "Kokoro speed must be between ${KokoroTtsPreferences.MIN_SPEED} and ${KokoroTtsPreferences.MAX_SPEED}"
        }

        val deferred = CompletableDeferred<KokoroDiagnosticAudio>(currentCoroutineContext()[Job])
        val work = synchronized(lifecycleLock) {
            if (isClosed) throw KokoroTtsOperationException(KokoroTtsError.cancelled("Kokoro engine is closed."))
            Work.Diagnostic(
                destination = destination,
                text = text,
                voiceSid = voiceSid,
                speed = speed,
                style = style,
                autoStyle = autoStyle,
                playAfterSynthesis = playAfterSynthesis,
                epoch = cancellationEpoch.get(),
                result = deferred,
            )
        }
        if (workQueue.trySend(work).isFailure) {
            throw KokoroTtsOperationException(KokoroTtsError.cancelled("Kokoro engine is closed."))
        }
        return deferred.await()
    }

    private suspend fun consumeWork() {
        try {
            for (work in workQueue) {
                val accepted = synchronized(lifecycleLock) {
                    if (!isClosed && work.epoch == cancellationEpoch.get()) {
                        activeWork = work
                        true
                    } else {
                        false
                    }
                }
                if (!accepted) {
                    flush(work)
                    continue
                }

                _engineState.value = KokoroEngineState.SYNTHESIZING
                var failed = false
                try {
                    try {
                        when (work) {
                            is Work.Speak -> processSpeak(work)
                            is Work.Diagnostic -> processDiagnostic(work)
                        }
                    } catch (error: Throwable) {
                        if (error is ThreadDeath || (error is VirtualMachineError && error !is OutOfMemoryError)) {
                            throw error
                        }
                        val mapped = mapSynthesisThrowable(error)
                        failed = true
                        _engineState.value = KokoroEngineState.FAILED
                        when (work) {
                            is Work.Speak -> {
                                if (isCurrent(work.epoch) { true }) notifyError(work.requestId, mapped)
                                else runCatching { listener?.onInterrupted(work.requestId) }
                            }
                            is Work.Diagnostic -> work.result.completeExceptionally(
                                KokoroTtsOperationException(mapped),
                            )
                        }
                    }
                } finally {
                    synchronized(lifecycleLock) {
                        if (activeWork === work) activeWork = null
                    }
                    if (!isClosed && !failed && _engineState.value != KokoroEngineState.FAILED) {
                        _engineState.value = KokoroEngineState.READY
                    }
                }
            }
        } finally {
            releaseOwnedResources()
        }
    }

    private fun processSpeak(work: Work.Speak) {
        val speechLanguage = SpeechLanguage.fromTag(work.language?.code) ?: LanguageDetector.detect(work.text)
        if (speechLanguage == SpeechLanguage.JA) {
            notifyError(
                work.requestId,
                KokoroTtsError(
                    KokoroTtsErrorCode.UNSUPPORTED_LANGUAGE,
                    "The packaged Kokoro v1.1-zh model does not provide a verified Japanese voice.",
                ),
            )
            return
        }
        val spokenText = when (speechLanguage) {
            SpeechLanguage.ZH -> ChineseTextNormalizer.normalize(work.text)
            SpeechLanguage.EN -> EnglishTextNormalizer.normalize(work.text)
            SpeechLanguage.JA -> error("Handled above")
        }
        if (spokenText.isBlank()) {
            notifyError(
                work.requestId,
                KokoroTtsError(KokoroTtsErrorCode.EMPTY_TEXT, "Cannot synthesize an empty utterance."),
            )
            return
        }
        val nativeSegments = splitForNative(spokenText, speechLanguage)

        val currentSettings = settings.value
        val stylePlan = stylePlan(
            text = work.text,
            style = currentSettings.effectiveStyle,
            automatic = currentSettings.autoStyle,
        )
        val parameters = SynthesisParameters(
            sid = currentSettings.effectiveVoiceSid,
            style = stylePlan.style,
            styleSpeed = stylePlan.parameters.speed,
            gain = stylePlan.parameters.gain,
            pauseMultiplier = stylePlan.parameters.pauseMultiplier,
        )

        val cacheKey = audioCache?.let {
            TtsCacheKey.create(
                TtsCacheKeyInput(
                    kokoroModelCommit = runtimeModel.manifest.huggingFaceCommit,
                    sherpaVersion = BuildConfig.SHERPA_ONNX_VERSION,
                    normalizedText = spokenText,
                    voiceSid = parameters.sid,
                    style = stylePlan.style,
                    tokenizerVersion = TOKENIZER_VERSION,
                    languageTag = speechLanguage.tag,
                    synthesisProfile = "${stylePlan.style.name.lowercase()}-silence-profile-v1",
                    normalizerVersion = if (speechLanguage == SpeechLanguage.EN) EnglishTextNormalizer.VERSION else "chinese-v2",
                ),
            )
        }
        val cached = cacheKey?.let { key -> runCatching { audioCache?.get(key) }.getOrNull() }
        if (cached != null) {
            audioCache?.protect(cached.file)
            try {
                when (
                    val playback = playSamples(
                        cached.samples,
                        cached.sampleRate,
                        work.epoch,
                        { !work.cancelled.get() },
                        { runCatching { listener?.onStart(work.requestId) } },
                    )
                ) {
                    PlaybackOutcome.Success -> runCatching { listener?.onDone(work.requestId) }
                    PlaybackOutcome.Cancelled -> runCatching { listener?.onInterrupted(work.requestId) }
                    is PlaybackOutcome.Failure -> notifyError(work.requestId, playback.error)
                }
            } finally {
                audioCache?.unprotect(cached.file)
            }
            return
        }

        val cacheAccumulator = Pcm16Accumulator()
        var sampleRate = 0
        var started = false
        for (segment in nativeSegments) {
            when (
                val outcome = synthesize(
                    text = segment,
                    parameters = parameters,
                    epoch = work.epoch,
                    cancelled = work.cancelled,
                    stillWanted = { true },
                )
            ) {
                SynthesisOutcome.Cancelled -> {
                    runCatching { listener?.onInterrupted(work.requestId) }
                    return
                }

                is SynthesisOutcome.Failure -> {
                    notifyError(work.requestId, outcome.error)
                    return
                }

                is SynthesisOutcome.Success -> {
                    if (sampleRate == 0) sampleRate = outcome.sampleRate
                    if (sampleRate != outcome.sampleRate) {
                        notifyError(
                            work.requestId,
                            KokoroTtsError(
                                KokoroTtsErrorCode.SYNTHESIS_FAILED,
                                "Kokoro changed sample rate between sentence segments.",
                            ),
                        )
                        return
                    }
                    if (cacheKey != null) cacheAccumulator.append(outcome.samples)
                    when (
                        val playback = playSamples(
                            outcome.samples,
                            outcome.sampleRate,
                            work.epoch,
                            { !work.cancelled.get() },
                            {
                                if (!started) {
                                    started = true
                                    runCatching { listener?.onStart(work.requestId) }
                                }
                            },
                        )
                    ) {
                        PlaybackOutcome.Success -> Unit
                        PlaybackOutcome.Cancelled -> {
                            runCatching { listener?.onInterrupted(work.requestId) }
                            return
                        }
                        is PlaybackOutcome.Failure -> {
                            notifyError(work.requestId, playback.error)
                            return
                        }
                    }
                }
            }
        }
        if (cacheKey != null) {
            val samples = cacheAccumulator.toArray()
            if (samples.isNotEmpty()) runCatching { audioCache?.put(cacheKey, samples, sampleRate) }
        }
        runCatching { listener?.onDone(work.requestId) }
    }

    private fun processDiagnostic(work: Work.Diagnostic) {
        if (!work.result.isActive) return

        val stylePlan = stylePlan(work.text, work.style, work.autoStyle)
        val spokenText = ChineseTextNormalizer.normalize(work.text)
        if (spokenText.isBlank()) {
            _engineState.value = KokoroEngineState.FAILED
            work.result.completeExceptionally(
                KokoroTtsOperationException(
                    KokoroTtsError(KokoroTtsErrorCode.EMPTY_TEXT, "Diagnostic text is empty after normalization."),
                ),
            )
            return
        }
        val nativeSegments = splitForNative(spokenText, SpeechLanguage.ZH)
        val parameters = SynthesisParameters(
            sid = work.voiceSid,
            style = stylePlan.style,
            styleSpeed = stylePlan.parameters.speed,
            gain = stylePlan.parameters.gain,
            pauseMultiplier = stylePlan.parameters.pauseMultiplier,
        )
        val accumulator = Pcm16Accumulator()
        var sampleRate = 0
        var synthesisDurationMillis = 0L
        for (segment in nativeSegments) {
            when (
                val outcome = synthesize(
                    text = segment,
                    parameters = parameters,
                    epoch = work.epoch,
                    cancelled = work.cancelled,
                    stillWanted = { work.result.isActive },
                )
            ) {
                SynthesisOutcome.Cancelled -> {
                    work.result.completeExceptionally(KokoroTtsOperationException(KokoroTtsError.cancelled()))
                    return
                }
                is SynthesisOutcome.Failure -> {
                    _engineState.value = KokoroEngineState.FAILED
                    work.result.completeExceptionally(KokoroTtsOperationException(outcome.error))
                    return
                }
                is SynthesisOutcome.Success -> {
                    if (sampleRate == 0) sampleRate = outcome.sampleRate
                    if (sampleRate != outcome.sampleRate) {
                        _engineState.value = KokoroEngineState.FAILED
                        work.result.completeExceptionally(
                            KokoroTtsOperationException(
                                KokoroTtsError(
                                    KokoroTtsErrorCode.SYNTHESIS_FAILED,
                                    "Kokoro changed sample rate between diagnostic sentence segments.",
                                ),
                            ),
                        )
                        return
                    }
                    accumulator.append(outcome.samples)
                    synthesisDurationMillis += outcome.synthesisDurationMillis
                }
            }
        }
        try {
            if (!isCurrent(work.epoch) { work.result.isActive && !work.cancelled.get() }) {
                throw KokoroTtsOperationException(KokoroTtsError.cancelled())
            }
            val samples = accumulator.toArray()
            if (samples.isEmpty() || sampleRate <= 0) {
                throw KokoroTtsOperationException(emptyGeneratedAudioError())
            }
            val wav = WavWriter.writeMonoPcm16(work.destination, samples, sampleRate)
            if (work.playAfterSynthesis) {
                when (
                    val playback = playSamples(
                        samples,
                        sampleRate,
                        work.epoch,
                        { work.result.isActive && !work.cancelled.get() },
                        {},
                    )
                ) {
                    PlaybackOutcome.Cancelled -> throw KokoroTtsOperationException(KokoroTtsError.cancelled())
                    is PlaybackOutcome.Failure -> throw KokoroTtsOperationException(playback.error)
                    PlaybackOutcome.Success -> Unit
                }
            }
            work.result.complete(
                KokoroDiagnosticAudio(
                    wav = wav,
                    synthesisDurationMillis = synthesisDurationMillis,
                    voiceSid = work.voiceSid,
                    speed = work.speed,
                    style = stylePlan.style,
                ),
            )
        } catch (error: KokoroTtsOperationException) {
            if (error.error.code != KokoroTtsErrorCode.CANCELLED) {
                _engineState.value = KokoroEngineState.FAILED
            }
            work.result.completeExceptionally(error)
        } catch (error: Throwable) {
            _engineState.value = KokoroEngineState.FAILED
            work.result.completeExceptionally(
                KokoroTtsOperationException(
                    KokoroTtsError.fromThrowable(
                        KokoroTtsErrorCode.SYNTHESIS_FAILED,
                        "Unable to save the diagnostic WAV.",
                        error,
                    ),
                ),
            )
        }
    }

    private fun synthesize(
        text: String,
        parameters: SynthesisParameters,
        epoch: Long,
        cancelled: AtomicBoolean,
        stillWanted: () -> Boolean,
    ): SynthesisOutcome {
        if (!isCurrent(epoch) { !cancelled.get() && stillWanted() }) return SynthesisOutcome.Cancelled
        if (text.isBlank()) {
            return SynthesisOutcome.Failure(
                KokoroTtsError(KokoroTtsErrorCode.EMPTY_TEXT, "Cannot send empty text to Kokoro."),
            )
        }
        require(text.isNotBlank())
        if (text.length > MAX_NATIVE_CHARACTERS) {
            return SynthesisOutcome.Failure(
                KokoroTtsError(
                    KokoroTtsErrorCode.TEXT_TOO_LONG,
                    "Kokoro sentence exceeds the $MAX_NATIVE_CHARACTERS character native limit.",
                ),
            )
        }

        val generated = try {
            synthesisBackend.generate(
                text,
                KokoroGenerationRequest(
                    silenceScale = (DEFAULT_SILENCE_SCALE * parameters.pauseMultiplier).coerceAtLeast(0f),
                    synthesisProfileSpeed = parameters.styleSpeed.coerceIn(0.85f, 1.15f),
                    voiceSid = parameters.sid,
                ),
            )
        } catch (error: Throwable) {
            return if (!isCurrent(epoch) { !cancelled.get() && stillWanted() }) {
                SynthesisOutcome.Cancelled
            } else {
                SynthesisOutcome.Failure(mapSynthesisThrowable(error))
            }
        }
        val synthesisDurationMillis = generated.generationDurationMs
        if (!isCurrent(epoch) { !cancelled.get() && stillWanted() }) {
            return SynthesisOutcome.Cancelled
        }
        if (generated.samples.isEmpty() || generated.sampleRate <= 0) {
            return SynthesisOutcome.Failure(emptyGeneratedAudioError())
        }
        val samples = applyGain(generated.samples, parameters.gain)
        if (samples.isEmpty()) return SynthesisOutcome.Failure(emptyGeneratedAudioError())
        return SynthesisOutcome.Success(
            samples = samples,
            sampleRate = generated.sampleRate,
            synthesisDurationMillis = synthesisDurationMillis,
        )
    }

    private fun splitForNative(text: String, language: SpeechLanguage): List<String> = when (language) {
        SpeechLanguage.EN -> EnglishSentenceTokenizer(maxCharacters = MAX_NATIVE_CHARACTERS).tokenize(text)
        SpeechLanguage.ZH -> ChineseSentenceTokenizer(maxCharacters = MAX_NATIVE_CHARACTERS).tokenize(text)
        SpeechLanguage.JA -> emptyList()
    }.map { it.text }

    private fun emptyGeneratedAudioError() = KokoroTtsError(
        KokoroTtsErrorCode.EMPTY_GENERATED_AUDIO,
        "sherpa-onnx completed without valid audio samples.",
    )

    private fun playSamples(
        samples: ShortArray,
        sampleRate: Int,
        epoch: Long,
        stillWanted: () -> Boolean,
        onPlaybackStarted: () -> Unit,
    ): PlaybackOutcome {
        if (samples.isEmpty()) return PlaybackOutcome.Success
        if (!isCurrent(epoch, stillWanted)) return PlaybackOutcome.Cancelled
        val track = try {
            createAudioTrack(sampleRate)
        } catch (error: Throwable) {
            return PlaybackOutcome.Failure(
                KokoroTtsError.fromThrowable(
                    KokoroTtsErrorCode.AUDIO_PLAYBACK_FAILED,
                    "Unable to initialize AudioTrack for Kokoro speech.",
                    error,
                ),
            )
        }
        onPlaybackStarted()
        return when (val write = writeSamples(track, samples, epoch, stillWanted)) {
            PlaybackOutcome.Success -> finishPlayback(
                track,
                expectedFrames = samples.size.toLong(),
                epoch = epoch,
                stillWanted = stillWanted,
            )
            else -> write.also { releaseTrack(track) }
        }
    }

    private fun createAudioTrack(sampleRate: Int): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minimumBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBuffer > 0) { "AudioTrack returned invalid minimum buffer size: $minimumBuffer" }
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            format,
            max(minimumBuffer, sampleRate / 2),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("AudioTrack did not initialize")
        }
        applyPlaybackSpeed(track, settings.value.effectiveSpeed)

        activeAudioTrack = track
        synchronized(pauseMonitor) {
            if (!isPaused) track.play()
        }
        return track
    }

    /** Media3's Readium Player adapter owns this value; native synthesis remains at profile speed. */
    private fun applyPlaybackSpeed(track: AudioTrack, speed: Double) {
        require(speed in KokoroTtsPreferences.MIN_SPEED..KokoroTtsPreferences.MAX_SPEED)
        track.playbackParams = PlaybackParams().setSpeed(speed.toFloat()).setPitch(1f)
    }

    private fun writeSamples(
        track: AudioTrack,
        samples: ShortArray,
        epoch: Long,
        stillWanted: () -> Boolean,
    ): PlaybackOutcome {
        var offset = 0
        while (offset < samples.size) {
            if (!awaitResume(epoch, stillWanted)) return PlaybackOutcome.Cancelled
            val written = try {
                track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            } catch (error: Throwable) {
                return PlaybackOutcome.Failure(
                    KokoroTtsError.fromThrowable(
                        KokoroTtsErrorCode.AUDIO_PLAYBACK_FAILED,
                        "AudioTrack failed while writing Kokoro PCM samples.",
                        error,
                    ),
                )
            }
            if (written <= 0) {
                return PlaybackOutcome.Failure(
                    KokoroTtsError(
                        KokoroTtsErrorCode.AUDIO_PLAYBACK_FAILED,
                        "AudioTrack write failed with code $written.",
                    ),
                )
            }
            offset += written
        }
        return PlaybackOutcome.Success
    }

    private fun finishPlayback(
        track: AudioTrack,
        expectedFrames: Long,
        epoch: Long,
        stillWanted: () -> Boolean,
    ): PlaybackOutcome {
        require(expectedFrames >= 0L)
        var lastHead = unsignedPlaybackHead(track)
        var lastProgressAt = System.nanoTime()
        return try {
            while (isCurrent(epoch, stillWanted)) {
                val wasPaused = isPaused
                if (!awaitResume(epoch, stillWanted)) return PlaybackOutcome.Cancelled
                if (wasPaused) lastProgressAt = System.nanoTime()
                val head = unsignedPlaybackHead(track)
                if (head != lastHead) {
                    lastHead = head
                    lastProgressAt = System.nanoTime()
                }
                if (head >= expectedFrames) break
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    if (!isPaused) {
                        return PlaybackOutcome.Failure(
                            KokoroTtsError(
                                KokoroTtsErrorCode.AUDIO_PLAYBACK_FAILED,
                                "AudioTrack stopped before Kokoro playback completed.",
                            ),
                        )
                    }
                }

                if (System.nanoTime() - lastProgressAt >= PLAYBACK_STALL_NANOS) {
                    return PlaybackOutcome.Failure(
                        KokoroTtsError(
                            KokoroTtsErrorCode.AUDIO_PLAYBACK_FAILED,
                            "AudioTrack made no playback progress before Kokoro audio completed.",
                        ),
                    )
                }
                Thread.sleep(PLAYBACK_POLL_MILLIS)
            }
            if (isCurrent(epoch, stillWanted)) PlaybackOutcome.Success else PlaybackOutcome.Cancelled
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            PlaybackOutcome.Cancelled
        } catch (error: Throwable) {
            PlaybackOutcome.Failure(
                KokoroTtsError.fromThrowable(
                    KokoroTtsErrorCode.AUDIO_PLAYBACK_FAILED,
                    "AudioTrack failed while completing Kokoro playback.",
                    error,
                ),
            )
        } finally {
            releaseTrack(track)
        }
    }

    private fun awaitResume(epoch: Long, stillWanted: () -> Boolean): Boolean {
        synchronized(pauseMonitor) {
            while (isPaused && isCurrent(epoch, stillWanted)) {
                try {
                    pauseMonitor.wait()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return isCurrent(epoch, stillWanted)
    }

    private fun isCurrent(epoch: Long, stillWanted: () -> Boolean): Boolean =
        !isClosed && cancellationEpoch.get() == epoch && stillWanted()

    private fun unsignedPlaybackHead(track: AudioTrack): Long =
        track.playbackHeadPosition.toLong() and 0xffff_ffffL

    private fun cancelCurrentAndFlushQueue(markClosed: Boolean) {
        val flushed = mutableListOf<Work>()
        var hadActiveWork = false
        synchronized(lifecycleLock) {
            if (isClosed && !markClosed) return
            _engineState.value = KokoroEngineState.STOPPING
            cancellationEpoch.incrementAndGet()
            activeWork?.let { active ->
                hadActiveWork = true
                active.cancelled.set(true)
            }
            while (true) {
                val work = workQueue.tryReceive().getOrNull() ?: break
                work.cancelled.set(true)
                flushed += work
            }
        }
        synchronized(pauseMonitor) {
            isPaused = false
            activeAudioTrack?.let { track ->
                runCatching { track.pause() }
                runCatching { track.flush() }
            }
            pauseMonitor.notifyAll()
        }
        flushed.forEach(::flush)
        if (!markClosed && !hadActiveWork) {
            _engineState.value = KokoroEngineState.READY
        }
    }

    private fun flush(work: Work) {
        when (work) {
            is Work.Speak -> runCatching { listener?.onFlushed(work.requestId) }
            is Work.Diagnostic -> work.result.completeExceptionally(
                KokoroTtsOperationException(KokoroTtsError.cancelled("Diagnostic request was flushed.")),
            )
        }
    }

    private fun releaseTrack(track: AudioTrack?) {
        if (track == null) return
        if (activeAudioTrack === track) activeAudioTrack = null
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private fun releaseOwnedResources() {
        releaseTrack(activeAudioTrack)
        try {
            runCatching { synthesisBackend.release() }
        } finally {
            _engineState.value = KokoroEngineState.RELEASED
            runCatching(onReleased)
            executor.shutdown()
        }
    }

    private fun notifyError(requestId: TtsEngine.RequestId, error: KokoroTtsError) {
        if (error.code != KokoroTtsErrorCode.CANCELLED) {
            _engineState.value = KokoroEngineState.FAILED
        }
        runCatching { listener?.onError(requestId, error) }
    }

    private fun mapSynthesisThrowable(error: Throwable): KokoroTtsError =
        if (error is UnsatisfiedLinkError || error is NoClassDefFoundError) {
            KokoroTtsError.fromThrowable(
                KokoroTtsErrorCode.NATIVE_LIBRARY_MISSING,
                "The sherpa-onnx ARM64 native library could not be loaded.",
                error,
            )
        } else {
            KokoroTtsError.fromThrowable(
                KokoroTtsErrorCode.SYNTHESIS_FAILED,
                if (error is OutOfMemoryError) {
                    "Kokoro synthesis ran out of memory. Shorten the utterance and try again."
                } else {
                    "Kokoro synthesis failed: ${error.message ?: error.javaClass.simpleName}"
                },
                error,
            )
        }

    private fun applyGain(samples: ShortArray, gain: Float): ShortArray {
        if (gain == 1f) return samples
        return ShortArray(samples.size) { index ->
            (samples[index] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun stylePlan(text: String, style: NarrationStyle, automatic: Boolean) =
        if (automatic) {
            NovelStylePlanner.plan(current = text, automatic = true)
        } else {
            NovelStylePlanner.plan(current = text, automatic = false, forcedStyle = style)
        }

    private sealed interface Work {
        val epoch: Long
        val cancelled: AtomicBoolean

        data class Speak(
            val requestId: TtsEngine.RequestId,
            val text: String,
            val language: Language?,
            override val epoch: Long,
            override val cancelled: AtomicBoolean = AtomicBoolean(false),
        ) : Work

        data class Diagnostic(
            val destination: File,
            val text: String,
            val voiceSid: Int,
            val speed: Double,
            val style: NarrationStyle,
            val autoStyle: Boolean,
            val playAfterSynthesis: Boolean,
            override val epoch: Long,
            val result: CompletableDeferred<KokoroDiagnosticAudio>,
            override val cancelled: AtomicBoolean = AtomicBoolean(false),
        ) : Work
    }

    private data class SynthesisParameters(
        val sid: Int,
        val style: NarrationStyle,
        val styleSpeed: Float,
        val gain: Float,
        val pauseMultiplier: Float,
    )

    private sealed interface SynthesisOutcome {
        data class Success(
            val samples: ShortArray,
            val sampleRate: Int,
            val synthesisDurationMillis: Long,
        ) : SynthesisOutcome

        data class Failure(val error: KokoroTtsError) : SynthesisOutcome
        data object Cancelled : SynthesisOutcome
    }

    private sealed interface PlaybackOutcome {
        data object Success : PlaybackOutcome
        data class Failure(val error: KokoroTtsError) : PlaybackOutcome
        data object Cancelled : PlaybackOutcome
    }

    private class Pcm16Accumulator {
        private val chunks = mutableListOf<ShortArray>()
        private var size = 0

        fun append(chunk: ShortArray) {
            if (chunk.isEmpty()) return
            size = Math.addExact(size, chunk.size)
            chunks += chunk
        }

        fun toArray(): ShortArray {
            val output = ShortArray(size)
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(output, offset)
                offset += chunk.size
            }
            return output
        }
    }

    companion object {
        private const val DEFAULT_SILENCE_SCALE = 0.2f
        private const val MAX_NATIVE_CHARACTERS = 120
        private const val PLAYBACK_POLL_MILLIS = 10L
        private const val PLAYBACK_STALL_NANOS = 5_000_000_000L
        private const val TOKENIZER_VERSION = "chinese-sentence-v1"

        private fun nanosToMillis(value: Long): Long = value / 1_000_000L
    }
}

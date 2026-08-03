@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.tl2333.novelvoicereader.tts.kokoro

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.tl2333.novelvoicereader.BuildConfig
import com.tl2333.novelvoicereader.tts.cache.TtsAudioCache
import com.tl2333.novelvoicereader.tts.cache.TtsCacheKey
import com.tl2333.novelvoicereader.tts.cache.TtsCacheKeyInput
import com.tl2333.novelvoicereader.tts.cache.WavInfo
import com.tl2333.novelvoicereader.tts.cache.WavWriter
import com.tl2333.novelvoicereader.tts.tokenizer.ChineseTextNormalizer
import com.tl2333.novelvoicereader.tts.tokenizer.NarrationStyle
import com.tl2333.novelvoicereader.tts.tokenizer.NovelStylePlanner
import java.io.File
import java.util.concurrent.Executors
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
    SYNTHESIS_FAILED,
    AUDIO_PLAYBACK_FAILED,
    CANCELLED,
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
 * The single worker owns [offlineTts] and every native inference call. Cancellation is cooperative:
 * sherpa-onnx v1.13.4 stops generation when its callback returns 0. AudioTrack is also owned and
 * released by the worker; calls from stop/pause only change its playback state.
 */
class KokoroTtsEngine internal constructor(
    private val offlineTts: OfflineTts,
    private val settingsResolver: KokoroTtsSettingsResolver,
    initialPreferences: KokoroTtsPreferences,
    val runtimeModel: KokoroRuntimeModel,
    val initializationDurationMillis: Long,
    private val audioCache: TtsAudioCache? = null,
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
        workerScope.launch { consumeWork() }
    }

    override fun submitPreferences(preferences: KokoroTtsPreferences) {
        check(!isClosed) { "Engine is closed." }
        _settings.value = settingsResolver.settings(preferences)
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

    /** Pauses AudioTrack and blocks the native generation callback until [resume] or [stop]. */
    fun pause() {
        if (isClosed) return
        synchronized(pauseMonitor) {
            isPaused = true
            runCatching { activeAudioTrack?.pause() }
        }
    }

    /** Resumes AudioTrack and lets the blocked generation callback continue. */
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
                }
            }
        } finally {
            releaseOwnedResources()
        }
    }

    private fun processSpeak(work: Work.Speak) {
        val spokenText = ChineseTextNormalizer.normalize(work.text)
        if (spokenText.isBlank()) {
            notifyError(
                work.requestId,
                KokoroTtsError(KokoroTtsErrorCode.SYNTHESIS_FAILED, "Cannot synthesize an empty utterance."),
            )
            return
        }

        val currentSettings = settings.value
        val stylePlan = stylePlan(
            text = work.text,
            style = currentSettings.effectiveStyle,
            automatic = currentSettings.autoStyle,
        )
        val parameters = SynthesisParameters(
            sid = currentSettings.effectiveVoiceSid,
            speed = currentSettings.effectiveSpeed,
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
                    speed = currentSettings.effectiveSpeed.toFloat(),
                    style = stylePlan.style,
                    tokenizerVersion = TOKENIZER_VERSION,
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
                        { true },
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

        var started = false
        val outcome = synthesize(
            text = spokenText,
            parameters = parameters,
            epoch = work.epoch,
            collectSamples = audioCache != null,
            streamPlayback = true,
            stillWanted = { true },
            onPlaybackStarted = {
                if (!started) {
                    started = true
                    runCatching { listener?.onStart(work.requestId) }
                }
            },
        )

        when (outcome) {
            is SynthesisOutcome.Success -> {
                val cacheSamples = outcome.samples
                if (cacheKey != null && cacheSamples?.isNotEmpty() == true) {
                    runCatching {
                        audioCache?.put(cacheKey, cacheSamples, outcome.sampleRate)
                    }
                }
                runCatching { listener?.onDone(work.requestId) }
            }
            is SynthesisOutcome.Cancelled -> runCatching { listener?.onInterrupted(work.requestId) }
            is SynthesisOutcome.Failure -> notifyError(work.requestId, outcome.error)
        }
    }

    private fun processDiagnostic(work: Work.Diagnostic) {
        if (!work.result.isActive) return

        val stylePlan = stylePlan(work.text, work.style, work.autoStyle)
        val spokenText = ChineseTextNormalizer.normalize(work.text)
        if (spokenText.isBlank()) {
            work.result.completeExceptionally(
                KokoroTtsOperationException(
                    KokoroTtsError(KokoroTtsErrorCode.SYNTHESIS_FAILED, "Diagnostic text is empty after normalization."),
                ),
            )
            return
        }
        val parameters = SynthesisParameters(
            sid = work.voiceSid,
            speed = work.speed,
            style = stylePlan.style,
            styleSpeed = stylePlan.parameters.speed,
            gain = stylePlan.parameters.gain,
            pauseMultiplier = stylePlan.parameters.pauseMultiplier,
        )
        when (
            val outcome = synthesize(
                text = spokenText,
                parameters = parameters,
                epoch = work.epoch,
                collectSamples = true,
                streamPlayback = false,
                stillWanted = { work.result.isActive },
                onPlaybackStarted = {},
            )
        ) {
            is SynthesisOutcome.Cancelled -> work.result.completeExceptionally(
                KokoroTtsOperationException(KokoroTtsError.cancelled()),
            )

            is SynthesisOutcome.Failure -> work.result.completeExceptionally(
                KokoroTtsOperationException(outcome.error),
            )

            is SynthesisOutcome.Success -> {
                try {
                    if (!isCurrent(work.epoch) { work.result.isActive }) {
                        throw KokoroTtsOperationException(KokoroTtsError.cancelled())
                    }
                    val samples = requireNotNull(outcome.samples)
                    val wav = WavWriter.writeMonoPcm16(work.destination, samples, outcome.sampleRate)
                    if (work.playAfterSynthesis) {
                        when (val playback = playSamples(samples, outcome.sampleRate, work.epoch, { work.result.isActive }, {})) {
                            is PlaybackOutcome.Cancelled -> throw KokoroTtsOperationException(KokoroTtsError.cancelled())
                            is PlaybackOutcome.Failure -> throw KokoroTtsOperationException(playback.error)
                            PlaybackOutcome.Success -> Unit
                        }
                    }
                    work.result.complete(
                        KokoroDiagnosticAudio(
                            wav = wav,
                            synthesisDurationMillis = outcome.synthesisDurationMillis,
                            voiceSid = work.voiceSid,
                            speed = work.speed,
                            style = stylePlan.style,
                        ),
                    )
                } catch (error: KokoroTtsOperationException) {
                    work.result.completeExceptionally(error)
                } catch (error: Throwable) {
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
        }
    }

    private fun synthesize(
        text: String,
        parameters: SynthesisParameters,
        epoch: Long,
        collectSamples: Boolean,
        streamPlayback: Boolean,
        stillWanted: () -> Boolean,
        onPlaybackStarted: () -> Unit,
    ): SynthesisOutcome {
        if (!isCurrent(epoch, stillWanted)) return SynthesisOutcome.Cancelled

        val sampleRate = try {
            offlineTts.sampleRate().also {
                if (it <= 0) error("sherpa-onnx returned an invalid sample rate: $it")
            }
        } catch (error: Throwable) {
            return SynthesisOutcome.Failure(mapSynthesisThrowable(error))
        }

        val accumulator = if (collectSamples) Pcm16Accumulator() else null
        var track: AudioTrack? = null
        var callbackError: KokoroTtsError? = null
        var receivedSamples = false
        var playbackStarted = false
        var writtenFrames = 0L
        var generatedSamples: FloatArray? = null
        var generatedSampleRate = sampleRate

        val startedAt = System.nanoTime()
        try {
            val generated = offlineTts.generateWithConfigAndCallback(
                text,
                GenerationConfig(
                    silenceScale = (DEFAULT_SILENCE_SCALE * parameters.pauseMultiplier).coerceAtLeast(0f),
                    speed = (parameters.speed.toFloat() * parameters.styleSpeed).coerceAtLeast(0.05f),
                    sid = parameters.sid,
                ),
            ) { chunk ->
                if (!awaitResume(epoch, stillWanted)) return@generateWithConfigAndCallback 0
                if (chunk.isEmpty()) return@generateWithConfigAndCallback 1

                receivedSamples = true
                val pcm = WavWriter.floatToPcm16(chunk, parameters.gain)
                accumulator?.append(pcm)
                if (streamPlayback) {
                    if (track == null) {
                        track = try {
                            createAudioTrack(sampleRate)
                        } catch (error: Throwable) {
                            callbackError = KokoroTtsError.fromThrowable(
                                KokoroTtsErrorCode.AUDIO_PLAYBACK_FAILED,
                                "Unable to initialize AudioTrack for Kokoro speech.",
                                error,
                            )
                            return@generateWithConfigAndCallback 0
                        }
                    }
                    if (!playbackStarted) {
                        playbackStarted = true
                        onPlaybackStarted()
                    }
                    when (val playback = writeSamples(requireNotNull(track), pcm, epoch, stillWanted)) {
                        PlaybackOutcome.Success -> writtenFrames += pcm.size.toLong()
                        PlaybackOutcome.Cancelled -> return@generateWithConfigAndCallback 0
                        is PlaybackOutcome.Failure -> {
                            callbackError = playback.error
                            return@generateWithConfigAndCallback 0
                        }
                    }
                }
                1
            }
            generatedSamples = generated.samples
            if (generated.sampleRate > 0) generatedSampleRate = generated.sampleRate
        } catch (error: Throwable) {
            releaseTrack(track)
            return if (!isCurrent(epoch, stillWanted)) {
                SynthesisOutcome.Cancelled
            } else {
                SynthesisOutcome.Failure(mapSynthesisThrowable(error))
            }
        }
        val synthesisDurationMillis = nanosToMillis(System.nanoTime() - startedAt)

        callbackError?.let {
            releaseTrack(track)
            return SynthesisOutcome.Failure(it)
        }
        if (!isCurrent(epoch, stillWanted)) {
            releaseTrack(track)
            return SynthesisOutcome.Cancelled
        }

        if (!receivedSamples && generatedSamples?.isNotEmpty() == true) {
            val pcm = WavWriter.floatToPcm16(requireNotNull(generatedSamples), parameters.gain)
            accumulator?.append(pcm)
            if (streamPlayback) {
                val fallbackTrack = try {
                    createAudioTrack(generatedSampleRate)
                } catch (error: Throwable) {
                    return SynthesisOutcome.Failure(
                        KokoroTtsError.fromThrowable(
                            KokoroTtsErrorCode.AUDIO_PLAYBACK_FAILED,
                            "Unable to initialize AudioTrack for Kokoro speech.",
                            error,
                        ),
                    )
                }
                track = fallbackTrack
                playbackStarted = true
                onPlaybackStarted()
                when (val playback = writeSamples(fallbackTrack, pcm, epoch, stillWanted)) {
                    PlaybackOutcome.Success -> writtenFrames += pcm.size.toLong()
                    PlaybackOutcome.Cancelled -> {
                        releaseTrack(fallbackTrack)
                        return SynthesisOutcome.Cancelled
                    }
                    is PlaybackOutcome.Failure -> {
                        releaseTrack(fallbackTrack)
                        return SynthesisOutcome.Failure(playback.error)
                    }
                }
                receivedSamples = true
            }
        }

        if (!receivedSamples) {
            releaseTrack(track)
            return SynthesisOutcome.Failure(
                KokoroTtsError(
                    KokoroTtsErrorCode.SYNTHESIS_FAILED,
                    "sherpa-onnx completed without producing audio samples.",
                ),
            )
        }

        if (streamPlayback) {
            when (
                val playback = finishPlayback(
                    requireNotNull(track),
                    expectedFrames = writtenFrames,
                    epoch = epoch,
                    stillWanted = stillWanted,
                )
            ) {
                PlaybackOutcome.Success -> Unit
                PlaybackOutcome.Cancelled -> return SynthesisOutcome.Cancelled
                is PlaybackOutcome.Failure -> return SynthesisOutcome.Failure(playback.error)
            }
        }

        val samples = accumulator?.toArray()
        if (collectSamples && samples?.isNotEmpty() != true) {
            return SynthesisOutcome.Failure(
                KokoroTtsError(KokoroTtsErrorCode.SYNTHESIS_FAILED, "Kokoro produced no PCM samples."),
            )
        }
        return SynthesisOutcome.Success(
            samples = samples,
            sampleRate = generatedSampleRate,
            synthesisDurationMillis = synthesisDurationMillis,
        )
    }

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

        activeAudioTrack = track
        synchronized(pauseMonitor) {
            if (!isPaused) track.play()
        }
        return track
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
        synchronized(lifecycleLock) {
            if (isClosed && !markClosed) return
            cancellationEpoch.incrementAndGet()
            while (true) {
                val work = workQueue.tryReceive().getOrNull() ?: break
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
        runCatching { offlineTts.release() }
        executor.shutdown()
    }

    private fun notifyError(requestId: TtsEngine.RequestId, error: KokoroTtsError) {
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

    private fun stylePlan(text: String, style: NarrationStyle, automatic: Boolean) =
        if (automatic) {
            NovelStylePlanner.plan(current = text, automatic = true)
        } else {
            NovelStylePlanner.plan(current = text, automatic = false, forcedStyle = style)
        }

    private sealed interface Work {
        val epoch: Long

        data class Speak(
            val requestId: TtsEngine.RequestId,
            val text: String,
            val language: Language?,
            override val epoch: Long,
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
        ) : Work
    }

    private data class SynthesisParameters(
        val sid: Int,
        val speed: Double,
        val style: NarrationStyle,
        val styleSpeed: Float,
        val gain: Float,
        val pauseMultiplier: Float,
    )

    private sealed interface SynthesisOutcome {
        data class Success(
            val samples: ShortArray?,
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
        private const val PLAYBACK_POLL_MILLIS = 10L
        private const val PLAYBACK_STALL_NANOS = 5_000_000_000L
        private const val TOKENIZER_VERSION = "chinese-sentence-v1"

        private fun nanosToMillis(value: Long): Long = value / 1_000_000L
    }
}

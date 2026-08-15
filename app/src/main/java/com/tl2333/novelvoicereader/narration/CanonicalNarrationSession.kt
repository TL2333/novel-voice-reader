package com.tl2333.novelvoicereader.narration

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tl2333.novelvoicereader.BuildConfig
import com.tl2333.novelvoicereader.content.model.CanonicalDocument
import com.tl2333.novelvoicereader.tts.cache.AudioCacheCapacityPolicy
import com.tl2333.novelvoicereader.tts.cache.CachedPcm16Audio
import com.tl2333.novelvoicereader.tts.cache.TtsAudioCache
import com.tl2333.novelvoicereader.tts.cache.TtsCacheKey
import com.tl2333.novelvoicereader.tts.cache.TtsCacheKeyInput
import com.tl2333.novelvoicereader.tts.cache.WavWriter
import com.tl2333.novelvoicereader.tts.client.TtsInferenceClient
import com.tl2333.novelvoicereader.tts.client.TtsProcessDiedException
import com.tl2333.novelvoicereader.tts.kokoro.KokoroGenerationRequest
import com.tl2333.novelvoicereader.tts.tokenizer.NarrationStyle
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CanonicalNarrationUiState(
    val narration: NarrationSnapshot = NarrationSnapshot(),
    val currentSegment: SpeechSegment? = null,
    val bufferWallMs: Long = 0,
    val preparedChunks: Int = 0,
    val rtf: RtfStatistics? = null,
    val message: String? = null,
)

/** Disk-backed narration for TXT/DOCX/PDF/WEB canonical readers. */
class CanonicalNarrationSession(
    context: Context,
    private val document: CanonicalDocument,
    private val voiceSid: Int,
    initialSpeed: Float,
    private val japaneseBackend: JapaneseTtsBackend = UnsupportedJapaneseTtsBackend,
) : AutoCloseable {
    private val application = context.applicationContext
    private val player = ExoPlayer.Builder(application).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val segments = SpeechPlanner().plan(document)
    private val rtf = RtfTracker()
    private val epochGuard = SynthesisEpochGuard()
    private var generationJob: Job? = null
    private var backend: TtsInferenceClient? = null
    private val timelines = mutableListOf<List<ChunkTimelineEntry>>()
    private val chunkDurations = mutableListOf<Long>()
    private val protectedCacheFiles = linkedSetOf<File>()
    private val trace = NarrationTrace(File(application.filesDir, "narration-traces"), document.id)
    private val cache = TtsAudioCache(
        File(application.cacheDir, "narration"),
        document.id,
        AudioCacheCapacityPolicy.capacityFor(application.cacheDir.usableSpace),
    )
    private val controller = NarrationController(object : NarrationPlaybackPort {
        override fun setPlaybackSpeed(speed: Float) {
            player.playbackParameters = PlaybackParameters(speed, 1f)
        }

        override fun play() { player.play() }
        override fun pause() { player.pause() }
        override fun stop() { player.stop() }
    })
    private val _state = MutableStateFlow(CanonicalNarrationUiState(narration = controller.snapshot))
    val state: StateFlow<CanonicalNarrationUiState> = _state.asStateFlow()

    init {
        controller.send(NarrationIntent.SetPlaybackSpeed(initialSpeed))
        _state.value = _state.value.copy(narration = controller.snapshot)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val entry = timelines.getOrNull(player.currentMediaItemIndex)?.firstOrNull()
                _state.value = _state.value.copy(
                    currentSegment = entry?.let { current -> segments.firstOrNull { it.id == current.segmentId } },
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && generationJob?.isCompleted == true) {
                    controller.accept(NarrationEvent.PlaybackCompleted)
                    trace.record(traceRecord(NarrationTraceEvent.PLAYBACK_END))
                    publish()
                } else if (playbackState == Player.STATE_ENDED && generationJob?.isActive == true) {
                    controller.accept(NarrationEvent.BufferLow)
                    trace.record(traceRecord(NarrationTraceEvent.BUFFER_EMPTY, bufferWallMs = 0))
                    publish(message = "缓冲已用尽，正在继续生成…")
                }
            }
        })
    }

    fun start(segmentIndex: Int = 0) {
        if (segments.isEmpty()) {
            _state.value = _state.value.copy(message = "文档没有可朗读文本。")
            return
        }
        val startIndex = segmentIndex.coerceIn(0, segments.lastIndex)
        val epoch = epochGuard.begin()
        generationJob?.cancel()
        protectedCacheFiles.forEach(cache::unprotect)
        protectedCacheFiles.clear()
        player.clearMediaItems()
        timelines.clear()
        chunkDurations.clear()
        controller.send(NarrationIntent.Start(document.id, startIndex))
        trace.record(traceRecord(NarrationTraceEvent.SESSION_START, segmentId = segments[startIndex].id))
        publish(message = "正在准备连续朗读…")
        generationJob = scope.launch {
            try {
                var engine = backend ?: TtsInferenceClient.connect(application).also { backend = it }
                var processRebindAttempted = false
                val performanceProfile = DevicePerformanceProfileDetector.detect(application)
                var runtimePlan = SynthesisRuntimePlanner.plan(
                    performanceProfile.tier,
                    Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                    performanceProfile.recentRtf?.p95,
                )
                var next = startIndex
                var playbackStarted = false
                while (next < segments.size && epochGuard.isCurrent(epoch)) {
                    val currentAdaptivePlan = AdaptiveBufferPlanner.plan(
                        performanceProfile.tier,
                        controller.snapshot.playbackSpeed,
                        rtf.statistics()?.p95 ?: performanceProfile.recentRtf?.p95,
                    )
                    if (playbackStarted) awaitGenerationCapacity(epoch, currentAdaptivePlan, runtimePlan)
                    controller.accept(NarrationEvent.SynthesisStarted(segments[next].id))
                    val parts = mutableListOf<SegmentPcm>()
                    var chunkMediaMs = 0L
                    val chunkPolicy = if (next == startIndex) runtimePlan.firstChunk else runtimePlan.followingChunks
                    while (
                        next < segments.size &&
                        epochGuard.isCurrent(epoch) &&
                        parts.size < chunkPolicy.maxSegments
                    ) {
                        val segment = segments[next]
                        trace.record(traceRecord(NarrationTraceEvent.SEGMENT_QUEUED, segmentId = segment.id, plannedPauseMs = segment.plannedPauseMs))
                        val pcm = try {
                            synthesizeSegment(engine, segment, epoch)
                        } catch (error: TtsProcessDiedException) {
                            trace.record(
                                traceRecord(
                                    NarrationTraceEvent.TTS_PROCESS_DIED,
                                    segmentId = segment.id,
                                    errorCode = NarrationErrorCode.TTS_ENGINE_DIED.name,
                                ),
                            )
                            if (processRebindAttempted) throw error
                            processRebindAttempted = true
                            engine.release()
                            backend = null
                            engine = TtsInferenceClient.connect(application).also { backend = it }
                            synthesizeSegment(engine, segment, epoch)
                        }
                        if (!epochGuard.isCurrent(epoch)) throw CancellationException("Stale synthesis epoch")
                        parts += SegmentPcm(segment, pcm.sampleRate, pcm.samples, pcm.file)
                        chunkMediaMs += pcm.samples.size * 1_000L / pcm.sampleRate + segment.plannedPauseMs
                        next++
                        if (parts.size >= chunkPolicy.minSegments && chunkMediaMs >= chunkPolicy.targetMinDurationMs) break
                        if (chunkMediaMs >= chunkPolicy.targetMaxDurationMs) break
                    }
                    if (!epochGuard.isCurrent(epoch)) throw CancellationException("Stale synthesis epoch")
                    val prepared = prepareChunk(parts)
                    controller.accept(NarrationEvent.SynthesisCompleted(parts.last().segment.id, prepared.durationMs))
                    val index = withContext(Dispatchers.Main) {
                        if (!epochGuard.isCurrent(epoch)) throw CancellationException("Stale synthesis epoch")
                        timelines += prepared.timeline
                        chunkDurations += prepared.durationMs
                        if (prepared.directCacheFile) {
                            cache.protect(prepared.file)
                            protectedCacheFiles += prepared.file
                        }
                        player.addMediaItem(MediaItem.fromUri(prepared.file.toURI().toString()))
                        val addedIndex = player.mediaItemCount - 1
                        if (playbackStarted && player.playbackState == Player.STATE_ENDED) {
                            player.seekTo(addedIndex, 0)
                            player.prepare()
                            controller.accept(NarrationEvent.BufferRecovered)
                            player.play()
                            trace.record(traceRecord(NarrationTraceEvent.BUFFER_RECOVERED))
                        }
                        addedIndex
                    }
                    trace.record(traceRecord(NarrationTraceEvent.CHUNK_READY, segmentId = parts.first().segment.id, chunkId = prepared.id, audioDurationMs = prepared.durationMs, queueDepth = index + 1))
                    val plan = AdaptiveBufferPlanner.plan(
                        performanceProfile.tier,
                        controller.snapshot.playbackSpeed,
                        rtf.statistics()?.p95 ?: performanceProfile.recentRtf?.p95,
                    )
                    runtimePlan = SynthesisRuntimePlanner.plan(
                        performanceProfile.tier,
                        Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                        rtf.statistics()?.p95 ?: performanceProfile.recentRtf?.p95,
                    )
                    val remainingMediaMs = withContext(Dispatchers.Main) { remainingQueuedMediaMs() }
                    val wall = AdaptiveBufferPlanner.bufferWallMs(remainingMediaMs, controller.snapshot.playbackSpeed)
                    if (playbackStarted && wall < plan.watermarks.lowWallMs) {
                        trace.record(traceRecord(NarrationTraceEvent.BUFFER_LOW, bufferWallMs = wall, queueDepth = index + 1))
                    }
                    _state.value = _state.value.copy(
                        narration = controller.snapshot,
                        currentSegment = _state.value.currentSegment ?: segments[startIndex],
                        bufferWallMs = wall,
                        preparedChunks = index + 1,
                        rtf = rtf.statistics(),
                        message = if (plan.aggressivePregeneration && wall < 60_000) "高速朗读预生成中" else "已准备 ${wall / 1_000} 秒",
                    )
                    if (!playbackStarted && (wall >= plan.warmStartWallMs || index == 0)) {
                        withContext(Dispatchers.Main) {
                            player.prepare()
                            controller.accept(NarrationEvent.PlaybackStarted)
                            trace.record(traceRecord(NarrationTraceEvent.PLAYBACK_START, bufferWallMs = wall, queueDepth = index + 1))
                            publish()
                        }
                        playbackStarted = true
                    }
                }
                if (!playbackStarted && timelines.isNotEmpty() && epochGuard.isCurrent(epoch)) {
                    withContext(Dispatchers.Main) {
                        player.prepare()
                        controller.accept(NarrationEvent.PlaybackStarted)
                        trace.record(traceRecord(NarrationTraceEvent.PLAYBACK_START, queueDepth = timelines.size))
                        publish()
                    }
                }
            } catch (error: TtsProcessDiedException) {
                if (epochGuard.isCurrent(epoch)) {
                    backend?.release()
                    backend = null
                    withContext(Dispatchers.Main) {
                        controller.accept(NarrationEvent.TtsProcessDied)
                        trace.record(traceRecord(NarrationTraceEvent.TTS_PROCESS_DIED, errorCode = NarrationErrorCode.TTS_ENGINE_DIED.name))
                        publish(message = "TTS 进程已退出；阅读位置已保留，可重新开始朗读。")
                    }
                }
            } catch (error: Throwable) {
                if (epochGuard.isCurrent(epoch)) {
                    controller.accept(NarrationEvent.SynthesisFailed(error.message ?: error.javaClass.simpleName))
                    trace.record(traceRecord(NarrationTraceEvent.ERROR, errorCode = NarrationErrorCode.SYNTHESIS_FAILED.name))
                    publish(message = "朗读失败：${error.message.orEmpty()}")
                }
            }
        }
    }

    fun startAtBlock(blockId: String) {
        start(segments.indexOfFirst { it.blockId == blockId }.takeIf { it >= 0 } ?: 0)
    }

    fun next() {
        val current = segments.indexOfFirst { it.id == _state.value.currentSegment?.id }
            .takeIf { it >= 0 } ?: controller.snapshot.segmentIndex
        controller.send(NarrationIntent.Next)
        start((current + 1).coerceAtMost(segments.lastIndex))
    }

    fun previous() {
        val current = segments.indexOfFirst { it.id == _state.value.currentSegment?.id }
            .takeIf { it >= 0 } ?: controller.snapshot.segmentIndex
        controller.send(NarrationIntent.Previous)
        start((current - 1).coerceAtLeast(0))
    }

    fun pauseOrResume() {
        if (controller.snapshot.state == NarrationState.PAUSED) controller.send(NarrationIntent.Resume)
        else controller.send(NarrationIntent.Pause)
        publish()
    }

    fun setPlaybackSpeed(speed: Float) {
        controller.send(NarrationIntent.SetPlaybackSpeed(speed))
        publish()
    }

    fun stop() {
        epochGuard.cancel()
        generationJob?.cancel()
        controller.send(NarrationIntent.Stop)
        trace.record(traceRecord(NarrationTraceEvent.SESSION_END))
        publish()
    }

    override fun close() {
        stop()
        scope.cancel()
        backend?.release()
        protectedCacheFiles.forEach(cache::unprotect)
        protectedCacheFiles.clear()
        player.release()
        controller.release()
    }

    private fun synthesizeSegment(engine: TtsInferenceClient, segment: SpeechSegment, epoch: Long): CachedPcm16Audio {
        if (segment.language == SpeechLanguage.JA) return japaneseBackend.synthesize(segment)
        val key = TtsCacheKey.create(
            TtsCacheKeyInput(
                BuildConfig.KOKORO_COMMIT,
                BuildConfig.SHERPA_ONNX_VERSION,
                segment.text,
                voiceSid,
                NarrationStyle.NEUTRAL,
                "canonical-v2",
                languageTag = segment.language.tag,
                synthesisProfile = "neutral-silence-0.2-standard-speed-v1",
                normalizerVersion = when (segment.language) {
                    SpeechLanguage.ZH -> "chinese-v2"
                    SpeechLanguage.EN -> EnglishTextNormalizer.VERSION
                    SpeechLanguage.JA -> "japanese-backend-v1"
                },
            ),
        )
        cache.get(key)?.let {
            trace.record(traceRecord(NarrationTraceEvent.CACHE_HIT, segmentId = segment.id, audioDurationMs = it.samples.size * 1_000L / it.sampleRate))
            return it
        }
        trace.record(traceRecord(NarrationTraceEvent.CACHE_MISS, segmentId = segment.id))
        trace.record(traceRecord(NarrationTraceEvent.SYNTHESIS_START, segmentId = segment.id))
        val generated = engine.generate(segment.text, KokoroGenerationRequest(0.2f, 1f, voiceSid))
        if (!epochGuard.isCurrent(epoch)) throw CancellationException("Stale synthesis epoch")
        val audioDurationMs = generated.samples.size * 1_000L / generated.sampleRate
        rtf.record(generated.generationDurationMs, audioDurationMs)
        trace.record(
            traceRecord(
                NarrationTraceEvent.SYNTHESIS_FINISH,
                segmentId = segment.id,
                generationMs = generated.generationDurationMs,
                audioDurationMs = audioDurationMs,
                rtf = generated.generationDurationMs.toDouble() / audioDurationMs,
            ),
        )
        val file = cache.put(key, generated.samples, generated.sampleRate)
        if (!epochGuard.isCurrent(epoch)) {
            file.delete()
            throw CancellationException("Stale synthesis epoch")
        }
        return CachedPcm16Audio(file, generated.samples, generated.sampleRate)
    }

    private fun prepareChunk(parts: List<SegmentPcm>): PreparedAudioChunk {
        require(parts.isNotEmpty())
        val direct = parts.singleOrNull()?.takeIf { it.sourceFile?.isFile == true }
        if (direct != null) {
            val duration = direct.samples.size * 1_000L / direct.sampleRate
            return PreparedAudioChunk(
                id = direct.segment.id,
                file = requireNotNull(direct.sourceFile),
                durationMs = duration,
                timeline = listOf(ChunkTimelineEntry(direct.segment.id, 0, duration, direct.segment.anchor)),
                directCacheFile = true,
            )
        }
        val chunk = ChunkAssembler.assemble(parts)
        val file = chunkFile(chunk.id)
        if (!file.isFile) WavWriter.writeMonoPcm16(file, chunk.samples, chunk.sampleRate)
        return PreparedAudioChunk(chunk.id, file, chunk.durationMs, chunk.timeline, false)
    }

    private suspend fun awaitGenerationCapacity(
        epoch: Long,
        adaptivePlan: AdaptiveBufferPlan,
        runtimePlan: SynthesisRuntimePlan,
    ) {
        while (epochGuard.isCurrent(epoch)) {
            val (wall, queuedAhead) = withContext(Dispatchers.Main) {
                val mediaMs = remainingQueuedMediaMs()
                val ahead = (player.mediaItemCount - player.currentMediaItemIndex.coerceAtLeast(0) - 1).coerceAtLeast(0)
                AdaptiveBufferPlanner.bufferWallMs(mediaMs, controller.snapshot.playbackSpeed) to ahead
            }
            if (
                wall < adaptivePlan.watermarks.highWallMs &&
                queuedAhead < runtimePlan.maximumQueuedChunksAhead
            ) return
            delay(250)
        }
        throw CancellationException("Stale synthesis epoch")
    }

    private fun chunkFile(id: String): File = File(application.cacheDir, "narration-chunks/${document.id}/$id.wav").apply {
        parentFile?.mkdirs()
    }

    private fun publish(message: String? = _state.value.message) {
        _state.value = _state.value.copy(narration = controller.snapshot, message = message)
    }

    private fun remainingQueuedMediaMs(): Long {
        if (chunkDurations.isEmpty()) return 0
        val current = player.currentMediaItemIndex.coerceIn(0, chunkDurations.lastIndex)
        val currentRemaining = (chunkDurations[current] - player.currentPosition.coerceAtLeast(0)).coerceAtLeast(0)
        return currentRemaining + chunkDurations.drop(current + 1).sum()
    }

    private fun traceRecord(
        event: NarrationTraceEvent,
        segmentId: String? = null,
        generationMs: Long? = null,
        audioDurationMs: Long? = null,
        rtf: Double? = null,
        bufferWallMs: Long? = _state.value.bufferWallMs,
        queueDepth: Int? = _state.value.preparedChunks,
        chunkId: String? = null,
        plannedPauseMs: Long? = null,
        errorCode: String? = null,
    ) = NarrationTraceRecord(
        event = event,
        segmentId = segmentId,
        generationMs = generationMs,
        audioDurationMs = audioDurationMs,
        rtf = rtf,
        playbackSpeed = controller.snapshot.playbackSpeed,
        bufferWallMs = bufferWallMs,
        queueDepth = queueDepth,
        chunkId = chunkId,
        plannedPauseMs = plannedPauseMs,
        errorCode = errorCode,
    )

    private data class PreparedAudioChunk(
        val id: String,
        val file: File,
        val durationMs: Long,
        val timeline: List<ChunkTimelineEntry>,
        val directCacheFile: Boolean,
    )
}

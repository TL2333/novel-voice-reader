package com.tl2333.novelvoicereader.tts.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.tl2333.novelvoicereader.tts.cache.WavWriter
import com.tl2333.novelvoicereader.tts.kokoro.KokoroModelLocator
import com.tl2333.novelvoicereader.narration.DevicePerformanceProfileDetector
import com.tl2333.novelvoicereader.narration.SynthesisRuntimePlanner
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/** The only process boundary allowed to load sherpa JNI and the Kokoro model. */
class TtsInferenceService : Service() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kokoro-inference").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private var offlineTts: OfflineTts? = null

    private val binder = object : ITtsInferenceService.Stub() {
        override fun initialize(): Bundle = execute { initializeLocked() }

        override fun synthesize(
            requestId: String,
            text: String,
            voiceSid: Int,
            synthesisProfileSpeed: Float,
            silenceScale: Float,
            outputPath: String,
        ): Bundle = execute {
            require(requestId.isNotBlank() && text.isNotBlank())
            require(text.length <= MAX_TEXT_CHARACTERS)
            require(voiceSid >= 0)
            require(synthesisProfileSpeed in 0.85f..1.15f)
            require(silenceScale in 0f..2f)
            val destination = validateOutputPath(outputPath)
            val engine = offlineTts ?: initializeEngine()
            val started = System.nanoTime()
            val generated = engine.generateWithConfig(
                text = text,
                config = GenerationConfig(
                    silenceScale = silenceScale,
                    speed = synthesisProfileSpeed,
                    sid = voiceSid,
                ),
            )
            check(generated.samples.isNotEmpty() && generated.sampleRate > 0) {
                "Kokoro returned empty audio"
            }
            destination.parentFile?.mkdirs()
            val part = File(destination.parentFile, "${destination.name}.part")
            try {
                val info = WavWriter.writeMonoPcm16(part, generated.samples, generated.sampleRate)
                check(info.sizeBytes > 44 && part.isFile)
                moveAtomically(part, destination)
                success().apply {
                    putString(KEY_PATH, destination.absolutePath)
                    putInt(KEY_SAMPLE_RATE, generated.sampleRate)
                    putLong(KEY_DURATION_MS, generated.samples.size * 1_000L / generated.sampleRate)
                    putLong(KEY_GENERATION_MS, (System.nanoTime() - started) / 1_000_000L)
                }
            } finally {
                if (part.exists()) part.delete()
            }
        }

        override fun releaseEngine() {
            worker.submit { releaseEngineLocked() }.get()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { worker.submit { releaseEngineLocked() }.get() }
        worker.shutdown()
        super.onDestroy()
    }

    private fun initializeLocked(): Bundle {
        val engine = offlineTts ?: initializeEngine()
        return success().apply {
            putInt(KEY_SAMPLE_RATE, engine.sampleRate())
            putInt(KEY_SPEAKER_COUNT, engine.numSpeakers())
        }
    }

    private fun initializeEngine(): OfflineTts {
        val runtime = runBlocking { KokoroModelLocator(this@TtsInferenceService).prepareRuntimeData() }
        val performance = DevicePerformanceProfileDetector.detect(this)
        val runtimePlan = SynthesisRuntimePlanner.plan(
            performance.tier,
            Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            performance.recentRtf?.p95,
        )
        return OfflineTts(assets, KokoroConfigFactory.create(runtime.espeakDataDirectory, runtimePlan.numThreads)).also {
            check(it.sampleRate() > 0 && it.numSpeakers() > 0)
            offlineTts = it
        }
    }

    private fun releaseEngineLocked() {
        val engine = offlineTts
        offlineTts = null
        engine?.release()
    }

    private fun validateOutputPath(path: String): File {
        val root = File(cacheDir, IPC_DIRECTORY).apply { mkdirs() }.canonicalFile
        val output = File(path).canonicalFile
        if (output.parentFile != root || output.extension.lowercase() != "wav") {
            throw SecurityException("TTS output must be a WAV directly inside $root")
        }
        return output
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        if (!destination.isFile || destination.length() <= 44L) throw IOException("Atomic WAV publish failed")
    }

    private fun execute(block: () -> Bundle): Bundle = try {
        worker.submit<Bundle>(block).get()
    } catch (error: Throwable) {
        Bundle().apply {
            putBoolean(KEY_SUCCESS, false)
            putString(KEY_ERROR, error.cause?.message ?: error.message ?: error.javaClass.simpleName)
        }
    }

    private fun success() = Bundle().apply { putBoolean(KEY_SUCCESS, true) }

    companion object {
        const val IPC_DIRECTORY = "tts-ipc"
        const val KEY_SUCCESS = "success"
        const val KEY_ERROR = "error"
        const val KEY_PATH = "path"
        const val KEY_SAMPLE_RATE = "sampleRate"
        const val KEY_SPEAKER_COUNT = "speakerCount"
        const val KEY_DURATION_MS = "durationMs"
        const val KEY_GENERATION_MS = "generationMs"
        private const val MAX_TEXT_CHARACTERS = 160
    }
}

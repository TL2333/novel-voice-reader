package com.tl2333.novelvoicereader.tts.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.DeadObjectException
import com.tl2333.novelvoicereader.tts.cache.readMonoPcm16Wav
import com.tl2333.novelvoicereader.tts.kokoro.KokoroGeneratedAudio
import com.tl2333.novelvoicereader.tts.kokoro.KokoroGenerationRequest
import com.tl2333.novelvoicereader.tts.kokoro.KokoroSynthesisBackend
import com.tl2333.novelvoicereader.tts.service.ITtsInferenceService
import com.tl2333.novelvoicereader.tts.service.TtsInferenceService
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TtsProcessDiedException(cause: Throwable? = null) : IOException("TTS engine process died", cause)

class TtsInferenceClient private constructor(
    private val context: Context,
    private val remote: ITtsInferenceService,
    private val connection: ServiceConnection,
    override val sampleRate: Int,
    override val speakerCount: Int,
) : KokoroSynthesisBackend {
    private val released = AtomicBoolean(false)
    private val binderDied = AtomicBoolean(false)
    private val deathRecipient = IBinder.DeathRecipient { binderDied.set(true) }

    init { remote.asBinder().linkToDeath(deathRecipient, 0) }

    override fun generate(text: String, request: KokoroGenerationRequest): KokoroGeneratedAudio {
        check(!released.get()) { "TTS client is released" }
        if (binderDied.get()) throw TtsProcessDiedException()
        val directory = File(context.cacheDir, TtsInferenceService.IPC_DIRECTORY).apply { mkdirs() }
        val output = File(directory, "${UUID.randomUUID()}.wav")
        try {
            val result = try {
                remote.synthesize(
                    UUID.randomUUID().toString(),
                    text,
                    request.voiceSid,
                    request.synthesisProfileSpeed,
                    request.silenceScale,
                    output.absolutePath,
                )
            } catch (error: DeadObjectException) {
                binderDied.set(true)
                throw TtsProcessDiedException(error)
            }
            checkResult(result.getBoolean(TtsInferenceService.KEY_SUCCESS), result.getString(TtsInferenceService.KEY_ERROR))
            val published = File(requireNotNull(result.getString(TtsInferenceService.KEY_PATH)))
            val audio = readMonoPcm16Wav(published)
            return KokoroGeneratedAudio(
                samples = audio.samples,
                sampleRate = audio.sampleRate,
                generationDurationMs = result.getLong(TtsInferenceService.KEY_GENERATION_MS),
            )
        } finally {
            output.delete()
            File(output.parentFile, "${output.name}.part").delete()
        }
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching { remote.releaseEngine() }
        runCatching { remote.asBinder().unlinkToDeath(deathRecipient, 0) }
        runCatching { context.unbindService(connection) }
    }

    companion object {
        private const val BIND_TIMEOUT_SECONDS = 60L

        fun connect(context: Context): TtsInferenceClient {
            val application = context.applicationContext
            val connected = CountDownLatch(1)
            var service: ITtsInferenceService? = null
            var disconnected = false
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    service = ITtsInferenceService.Stub.asInterface(binder)
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    disconnected = true
                    connected.countDown()
                }

                override fun onBindingDied(name: ComponentName) {
                    disconnected = true
                    connected.countDown()
                }
            }
            val intent = Intent(application, TtsInferenceService::class.java)
            check(application.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                "Unable to bind TTS inference service"
            }
            try {
                check(connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Timed out binding TTS inference service" }
                check(!disconnected)
                val remote = checkNotNull(service)
                val status = remote.initialize()
                checkResult(status.getBoolean(TtsInferenceService.KEY_SUCCESS), status.getString(TtsInferenceService.KEY_ERROR))
                return TtsInferenceClient(
                    application,
                    remote,
                    connection,
                    status.getInt(TtsInferenceService.KEY_SAMPLE_RATE),
                    status.getInt(TtsInferenceService.KEY_SPEAKER_COUNT),
                )
            } catch (error: Throwable) {
                runCatching { application.unbindService(connection) }
                throw error
            }
        }

        private fun checkResult(success: Boolean, message: String?) {
            if (!success) throw IOException(message ?: "TTS inference service failed")
        }
    }
}

package com.tl2333.novelvoicereader.tts.diagnostics

import android.content.Context
import android.os.Build
import com.tl2333.novelvoicereader.data.preferences.ReaderTtsPreferencesStore
import com.tl2333.novelvoicereader.tts.kokoro.KokoroModelFailure
import com.tl2333.novelvoicereader.tts.kokoro.KokoroModelPreparationException
import com.tl2333.novelvoicereader.tts.kokoro.KokoroTtsEngine
import com.tl2333.novelvoicereader.tts.kokoro.KokoroTtsEngineProvider
import com.tl2333.novelvoicereader.tts.kokoro.KokoroTtsError
import com.tl2333.novelvoicereader.tts.kokoro.KokoroTtsErrorCode
import com.tl2333.novelvoicereader.tts.kokoro.KokoroTtsOperationException
import com.tl2333.novelvoicereader.tts.kokoro.KokoroTtsPreferences
import com.tl2333.novelvoicereader.tts.tokenizer.NarrationStyle
import com.tl2333.novelvoicereader.ui.diagnostics.DiagnosticsActionResult
import com.tl2333.novelvoicereader.ui.diagnostics.DiagnosticsController
import com.tl2333.novelvoicereader.ui.diagnostics.DiagnosticsUiState
import com.tl2333.novelvoicereader.ui.diagnostics.ModelIntegrity
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.Try

/** Real, offline diagnostics. It never falls back to Android TextToSpeech or generated test tones. */
class KokoroDiagnosticController(
    application: Context,
    private val provider: KokoroTtsEngineProvider,
    private val preferencesStore: ReaderTtsPreferencesStore? = null,
) : DiagnosticsController {
    private val applicationContext = application.applicationContext
    private val engineMutex = Mutex()
    private val diagnosticOperationMutex = Mutex()
    private val outputSequence = AtomicLong(0L)

    @Volatile
    private var engine: KokoroTtsEngine? = null

    @Volatile
    private var selectedStyle: NarrationStyle = NarrationStyle.NEUTRAL

    @Volatile
    private var automaticStyle: Boolean = true

    private val initialManifest = runCatching { provider.modelLocator.loadManifest() }.getOrNull()
    private val _state = MutableStateFlow(
        DiagnosticsUiState(
            supportedAbi = Build.SUPPORTED_ABIS.joinToString().ifBlank { Build.CPU_ABI },
            modelPath = "apk-assets://${com.tl2333.novelvoicereader.tts.kokoro.KokoroAssetLayout.ASSET_DIRECTORY}",
            modelSizeBytes = initialManifest?.totalSizeBytes,
            message = "Kokoro 离线引擎尚未验证。",
            phase = "Ready",
            canPrepareModel = true,
            canGenerateTestSpeech = true,
        ),
    )
    override val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    override suspend fun refresh(): DiagnosticsActionResult {
        syncSharedPreferences()
        if (!supportsArm64()) return unsupportedAbi()
        _state.value = _state.value.copy(
            modelIntegrity = ModelIntegrity.CHECKING,
            message = "正在校验 APK 中的 Kokoro 模型资源…",
            phase = "Verifying model",
            busy = true,
            errorCode = null,
        )
        return try {
            val manifest = provider.modelLocator.loadManifest()
            val verification = provider.modelLocator.verifyAllPackagedAssets { progress ->
                _state.value = _state.value.copy(
                    phase = progress.phase,
                    currentFile = progress.currentFile,
                    processedBytes = progress.processedBytes,
                    totalBytes = progress.totalBytes,
                )
            }
            val integrity = when {
                verification.missingPaths.isNotEmpty() -> ModelIntegrity.MISSING
                verification.corruptPaths.isNotEmpty() -> ModelIntegrity.INVALID
                else -> ModelIntegrity.VALID
            }
            val message = if (verification.isValid) {
                "Kokoro 模型资源完整，已校验 ${verification.verifiedBytes} 字节。"
            } else {
                "模型校验失败；缺失=${verification.missingPaths}, 损坏=${verification.corruptPaths}"
            }
            _state.value = _state.value.copy(
                modelIntegrity = integrity,
                modelSizeBytes = manifest.totalSizeBytes,
                message = message,
                phase = if (verification.isValid) "Verified" else "Verification failed",
                currentFile = null,
                processedBytes = verification.verifiedBytes,
                totalBytes = manifest.totalSizeBytes,
                busy = false,
                canGenerateTestSpeech = verification.isValid,
                errorCode = if (verification.isValid) null else modelErrorCode(integrity),
            )
            if (verification.isValid) {
                DiagnosticsActionResult.Completed(message)
            } else {
                DiagnosticsActionResult.Rejected(requireNotNull(_state.value.errorCode), message)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            reject(mapPreparationError(error))
        }
    }

    override suspend fun prepareModel(): DiagnosticsActionResult =
        diagnosticOperationMutex.withLock { prepareModelLocked() }

    private suspend fun prepareModelLocked(): DiagnosticsActionResult {
        syncSharedPreferences()
        if (!supportsArm64()) return unsupportedAbi()
        _state.value = _state.value.copy(
            message = "正在准备 Kokoro 运行数据…",
            phase = "Preparing model",
            busy = true,
            errorCode = null,
        )
        var readyEngine: KokoroTtsEngine? = null
        return try {
            _state.value = _state.value.copy(
                message = "正在清理上一份 Kokoro 运行数据…",
                phase = "Cleaning runtime data",
                currentFile = null,
                processedBytes = 0L,
                totalBytes = 0L,
            )
            provider.modelLocator.resetRuntimeData()
            val runtime = provider.modelLocator.prepareRuntimeData { progress ->
                _state.value = _state.value.copy(
                    phase = progress.phase,
                    currentFile = progress.currentFile,
                    processedBytes = progress.processedBytes,
                    totalBytes = progress.totalBytes,
                )
            }
            readyEngine = requireEngine()
            val message = "Kokoro 已初始化，采样率由真实模型在生成时报告。"
            _state.value = _state.value.copy(
                modelPath = "apk-assets://kokoro; espeak=${runtime.espeakDataDirectory.absolutePath}",
                modelIntegrity = ModelIntegrity.VALID,
                modelSizeBytes = runtime.manifest.totalSizeBytes,
                initializationMillis = requireNotNull(readyEngine).initializationDurationMillis,
                message = message,
                phase = "Engine ready",
                currentFile = null,
                processedBytes = runtime.manifest.totalSizeBytes,
                totalBytes = runtime.manifest.totalSizeBytes,
                busy = false,
                canGenerateTestSpeech = true,
                errorCode = null,
            )
            DiagnosticsActionResult.Completed(message)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            reject(error.toKokoroError())
        } finally {
            releaseEngine(readyEngine)
        }
    }

    override suspend fun generateTestSpeech(): DiagnosticsActionResult =
        diagnosticOperationMutex.withLock { generateTestSpeechLocked() }

    private suspend fun generateTestSpeechLocked(): DiagnosticsActionResult {
        syncSharedPreferences()
        if (!supportsArm64()) return unsupportedAbi()
        _state.value = _state.value.copy(
            message = "正在用真实 Kokoro 模型生成测试语音…",
            phase = "Synthesizing",
            busy = true,
            canStopAudio = true,
            errorCode = null,
        )
        var readyEngine: KokoroTtsEngine? = null
        return try {
            readyEngine = requireEngine()
            val current = _state.value
            val outputDirectory = File(applicationContext.cacheDir, "tts-diagnostics")
            val output = File(outputDirectory, "kokoro-test-${outputSequence.incrementAndGet()}.wav")
            val audio = requireNotNull(readyEngine).synthesizeDiagnostic(
                destination = output,
                text = TEST_TEXT,
                voiceSid = current.currentVoiceSid,
                speed = current.narrationSpeed.toDouble(),
                style = selectedStyle,
                autoStyle = automaticStyle,
                playAfterSynthesis = true,
            )
            val message = "真实 Kokoro 测试语音已生成并播放。"
            _state.value = _state.value.copy(
                initializationMillis = requireNotNull(readyEngine).initializationDurationMillis,
                synthesisMillis = audio.synthesisDurationMillis,
                audioDurationMillis = audio.wav.durationMillis,
                sampleRate = audio.wav.sampleRate,
                outputPath = audio.wav.file.absolutePath,
                message = message,
                phase = "Completed",
                busy = false,
                canStopAudio = false,
                errorCode = null,
            )
            DiagnosticsActionResult.Completed(message)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            reject(error.toKokoroError())
        } finally {
            releaseEngine(readyEngine)
        }
    }

    override fun stopAudio() {
        engine?.stop()
        _state.value = _state.value.copy(
            message = "测试语音已停止。",
            phase = "Stopped",
            busy = false,
            canStopAudio = false,
            errorCode = KokoroTtsErrorCode.CANCELLED.name,
        )
    }

    fun pauseAudio() {
        engine?.pause()
        _state.value = _state.value.copy(message = "测试语音已暂停。", phase = "Paused")
    }

    fun resumeAudio() {
        engine?.resume()
        _state.value = _state.value.copy(message = "测试语音继续播放。", phase = "Playing")
    }

    fun selectVoice(sid: Int) {
        require(com.tl2333.novelvoicereader.tts.kokoro.KokoroVoiceCatalog.bySid(sid) != null)
        _state.value = _state.value.copy(currentVoiceSid = sid)
        updateEnginePreferences()
    }

    fun setNarrationSpeed(speed: Float) {
        require(speed.toDouble() in KokoroTtsPreferences.MIN_SPEED..KokoroTtsPreferences.MAX_SPEED)
        _state.value = _state.value.copy(narrationSpeed = speed)
        updateEnginePreferences()
    }

    fun close() {
        engine?.close()
        engine = null
    }

    private suspend fun requireEngine(): KokoroTtsEngine {
        engine?.let { return it }
        return engineMutex.withLock {
            engine?.let { return@withLock it }
            val preferences = KokoroTtsPreferences(
                voiceSid = _state.value.currentVoiceSid,
                speed = _state.value.narrationSpeed.toDouble(),
                style = selectedStyle,
                autoStyle = automaticStyle,
            )
            when (val result = provider.createDiagnosticEngine(preferences)) {
                is Try.Success -> result.value.also { engine = it }
                is Try.Failure -> throw DiagnosticInitializationException(result.value)
            }
        }
    }

    /** A diagnostic native session exists only for the duration of one serialized operation. */
    private suspend fun releaseEngine(expected: KokoroTtsEngine?) {
        expected ?: return
        withContext(NonCancellable) {
            engineMutex.withLock {
                if (engine === expected) engine = null
            }
            expected.close()
        }
    }

    private fun updateEnginePreferences() {
        val current = _state.value
        runCatching {
            engine?.submitPreferences(
                KokoroTtsPreferences(
                    voiceSid = current.currentVoiceSid,
                    speed = current.narrationSpeed.toDouble(),
                    style = selectedStyle,
                    autoStyle = automaticStyle,
                ),
            )
        }
    }

    private suspend fun syncSharedPreferences() {
        val shared = preferencesStore?.preferences?.first() ?: return
        selectedStyle = shared.narrationStyle
        automaticStyle = shared.automaticStyle
        _state.value = _state.value.copy(
            currentVoiceSid = shared.defaultVoiceSid,
            narrationSpeed = shared.narrationSpeed,
        )
        updateEnginePreferences()
    }

    private fun reject(error: KokoroTtsError): DiagnosticsActionResult.Rejected {
        _state.value = _state.value.copy(
            message = error.message,
            phase = "Failed",
            busy = false,
            canStopAudio = false,
            errorCode = error.code.name,
            modelIntegrity = when (error.code) {
                KokoroTtsErrorCode.MODEL_MISSING -> ModelIntegrity.MISSING
                KokoroTtsErrorCode.MODEL_CORRUPT -> ModelIntegrity.INVALID
                else -> _state.value.modelIntegrity
            },
        )
        return DiagnosticsActionResult.Rejected(error.code.name, error.message)
    }

    private fun unsupportedAbi(): DiagnosticsActionResult.Rejected {
        val error = KokoroTtsError(
            KokoroTtsErrorCode.NATIVE_LIBRARY_MISSING,
            "当前设备 ABI 为 ${Build.SUPPORTED_ABIS.joinToString()}；此 APK 只支持 arm64-v8a。",
        )
        return reject(error)
    }

    private fun supportsArm64(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    private fun Throwable.toKokoroError(): KokoroTtsError = when (this) {
        is KokoroTtsOperationException -> error
        is DiagnosticInitializationException -> when (val source = readiumError) {
            is KokoroTtsError -> source
            else -> KokoroTtsError(
                KokoroTtsErrorCode.ENGINE_INIT_FAILED,
                "Kokoro 初始化失败：${source.message}",
                source,
            )
        }
        else -> mapPreparationError(this)
    }

    private fun mapPreparationError(error: Throwable): KokoroTtsError =
        if (error is KokoroModelPreparationException) {
            when (error.failure) {
                KokoroModelFailure.MISSING -> KokoroTtsError.fromThrowable(
                    KokoroTtsErrorCode.MODEL_MISSING,
                    "Kokoro 模型文件缺失：${error.message}",
                    error,
                )
                KokoroModelFailure.CORRUPT -> KokoroTtsError.fromThrowable(
                    KokoroTtsErrorCode.MODEL_CORRUPT,
                    "Kokoro 模型校验失败：${error.message}",
                    error,
                )
                KokoroModelFailure.STORAGE -> KokoroTtsError.fromThrowable(
                    KokoroTtsErrorCode.ENGINE_INIT_FAILED,
                    "手机存储不足或无法写入 Kokoro 运行数据：${error.message}",
                    error,
                )
            }
        } else {
            KokoroTtsError.fromThrowable(
                KokoroTtsErrorCode.ENGINE_INIT_FAILED,
                "Kokoro 诊断失败：${error.message ?: error.javaClass.simpleName}",
                error,
            )
        }

    private fun modelErrorCode(integrity: ModelIntegrity): String = when (integrity) {
        ModelIntegrity.MISSING -> KokoroTtsErrorCode.MODEL_MISSING.name
        else -> KokoroTtsErrorCode.MODEL_CORRUPT.name
    }

    private class DiagnosticInitializationException(
        val readiumError: Error,
    ) : Exception(readiumError.message)

    companion object {
        const val TEST_TEXT = "夜色渐渐沉了下来，远处的灯火在雨幕中忽明忽暗。"
    }
}

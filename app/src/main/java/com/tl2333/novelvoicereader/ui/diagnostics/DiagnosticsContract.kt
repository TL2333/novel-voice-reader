package com.tl2333.novelvoicereader.ui.diagnostics

import com.tl2333.novelvoicereader.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ModelIntegrity { UNKNOWN, CHECKING, VALID, INVALID, MISSING }

data class DiagnosticsUiState(
    val appVersion: String = BuildConfig.VERSION_NAME,
    val supportedAbi: String = "arm64-v8a",
    val sherpaVersion: String = BuildConfig.SHERPA_ONNX_VERSION,
    val kokoroCommit: String = BuildConfig.KOKORO_COMMIT,
    val modelPath: String? = null,
    val modelIntegrity: ModelIntegrity = ModelIntegrity.UNKNOWN,
    val modelSizeBytes: Long? = null,
    val currentVoiceSid: Int = 3,
    val narrationSpeed: Float = 1f,
    val initializationMillis: Long? = null,
    val synthesisMillis: Long? = null,
    val audioDurationMillis: Long? = null,
    val sampleRate: Int? = null,
    val outputPath: String? = null,
    val errorCode: String? = null,
    val message: String,
    val phase: String = "Unavailable",
    val currentFile: String? = null,
    val processedBytes: Long = 0,
    val totalBytes: Long = 0,
    val busy: Boolean = false,
    val canPrepareModel: Boolean = false,
    val canGenerateTestSpeech: Boolean = false,
    val canStopAudio: Boolean = false,
)

sealed interface DiagnosticsActionResult {
    data class Completed(val message: String) : DiagnosticsActionResult
    data class Rejected(val errorCode: String, val message: String) : DiagnosticsActionResult
}

/** Narrow boundary implemented by the concrete Kokoro integration; it never substitutes fake audio. */
interface DiagnosticsController {
    val state: StateFlow<DiagnosticsUiState>
    suspend fun refresh(): DiagnosticsActionResult
    suspend fun prepareModel(): DiagnosticsActionResult
    suspend fun generateTestSpeech(): DiagnosticsActionResult
    fun stopAudio()
}

class UnavailableDiagnosticsController(
    private val reason: String = "Kokoro 引擎尚未连接，离线朗读已禁用。",
) : DiagnosticsController {
    private val mutableState = MutableStateFlow(
        DiagnosticsUiState(
            errorCode = ERROR_CODE,
            message = reason,
            phase = "Engine unavailable",
        ),
    )
    override val state: StateFlow<DiagnosticsUiState> = mutableState.asStateFlow()

    override suspend fun refresh(): DiagnosticsActionResult =
        DiagnosticsActionResult.Rejected(ERROR_CODE, reason)

    override suspend fun prepareModel(): DiagnosticsActionResult =
        DiagnosticsActionResult.Rejected(ERROR_CODE, reason)

    override suspend fun generateTestSpeech(): DiagnosticsActionResult =
        DiagnosticsActionResult.Rejected(ERROR_CODE, reason)

    override fun stopAudio() = Unit

    companion object {
        const val ERROR_CODE = "ENGINE_NOT_WIRED"
    }
}

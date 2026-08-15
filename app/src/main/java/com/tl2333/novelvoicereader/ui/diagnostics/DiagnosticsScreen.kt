package com.tl2333.novelvoicereader.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tl2333.novelvoicereader.tts.kokoro.KokoroVoiceCatalog
import com.tl2333.novelvoicereader.ui.components.ScreenScaffold
import com.tl2333.novelvoicereader.ui.components.formatBytes

@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectVoice: () -> Unit,
    onGenerateTestSpeech: () -> Unit,
    onStopAudio: () -> Unit,
    onExportTrace: () -> Unit,
    onBenchmark: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    ScreenScaffold(title = "离线朗读诊断", onBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DiagnosticSummary(state)
            Button(modifier = Modifier.fillMaxWidth(), enabled = !state.busy, onClick = onRefresh) {
                Text("重新检查")
            }
            Button(modifier = Modifier.fillMaxWidth(), enabled = !state.busy, onClick = onSelectVoice) {
                Text("选择测试声音 · sid ${state.currentVoiceSid}")
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canGenerateTestSpeech && !state.busy,
                onClick = onGenerateTestSpeech,
            ) { Text("生成测试语音") }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canStopAudio,
                onClick = onStopAudio,
            ) { Text("停止测试语音") }
            Button(modifier = Modifier.fillMaxWidth(), onClick = onExportTrace) {
                Text("导出朗读诊断日志")
            }
            Button(modifier = Modifier.fillMaxWidth(), enabled = !state.busy, onClick = onBenchmark) {
                Text("朗读性能测试（20 段）")
            }
            if (!state.canGenerateTestSpeech) Text("测试语音不可用：${state.errorCode ?: state.message}")
            if (state.busy) CircularProgressIndicator()
        }
    }
}

@Composable
fun ModelPreparationScreen(
    state: DiagnosticsUiState,
    prepareOnEnter: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPrepare: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (prepareOnEnter) onPrepare() else onRefresh()
    }
    ScreenScaffold(title = "离线模型准备", onBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("阶段：${state.phase}")
            Text("当前文件：${state.currentFile ?: "无"}")
            Text("进度：${formatBytes(state.processedBytes)} / ${formatBytes(state.totalBytes)}")
            if (state.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = {
                        (state.processedBytes.toDouble() / state.totalBytes)
                            .coerceIn(0.0, 1.0)
                            .toFloat()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("校验：${state.modelIntegrity}")
            Text(state.message)
            Button(modifier = Modifier.fillMaxWidth(), enabled = !state.busy, onClick = onRefresh) {
                Text("检查模型状态")
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canPrepareModel && !state.busy,
                onClick = onPrepare,
            ) { Text("清理并重新准备模型") }
            if (!state.canPrepareModel) Text("模型准备不可用：${state.errorCode ?: state.message}")
            if (state.busy) CircularProgressIndicator()
        }
    }
}

@Composable
private fun DiagnosticSummary(state: DiagnosticsUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("App ${state.appVersion} · ${state.supportedAbi}")
            Text("sherpa-onnx ${state.sherpaVersion}")
            Text("Kokoro ${state.kokoroCommit}")
            Text("模型路径：${state.modelPath ?: "未就绪"}")
            Text("模型完整性：${state.modelIntegrity}")
            Text("模型大小：${formatBytes(state.modelSizeBytes)}")
            Text("可用中文声音：${KokoroVoiceCatalog.voices.size} 个（sid 3–102）")
            Text(
                "默认女声 ${KokoroVoiceCatalog.DEFAULT_FEMALE_NAME} / sid ${KokoroVoiceCatalog.DEFAULT_FEMALE_SID} · " +
                    "默认男声 ${KokoroVoiceCatalog.DEFAULT_MALE_NAME} / sid ${KokoroVoiceCatalog.DEFAULT_MALE_SID}",
            )
            Text("声音 sid：${state.currentVoiceSid}")
            Text("速度：${"%.2f".format(state.narrationSpeed)}")
            Text("初始化耗时：${state.initializationMillis?.let { "$it ms" } ?: "未执行"}")
            Text("合成耗时：${state.synthesisMillis?.let { "$it ms" } ?: "未执行"}")
            Text("音频时长：${state.audioDurationMillis?.let { "$it ms" } ?: "未执行"}")
            Text("采样率：${state.sampleRate?.let { "$it Hz" } ?: "未执行"}")
            Text("输出：${state.outputPath ?: "无"}")
            Text("错误代码：${state.errorCode ?: "无"}")
            Text(state.message)
        }
    }
}

package com.tl2333.novelvoicereader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tl2333.novelvoicereader.data.preferences.ReaderNavigationMode
import com.tl2333.novelvoicereader.data.preferences.ReaderTextAlignment
import com.tl2333.novelvoicereader.data.preferences.ReaderTheme
import com.tl2333.novelvoicereader.data.preferences.ReaderTtsPreferences
import com.tl2333.novelvoicereader.ui.components.ScreenScaffold

@Composable
fun SettingsScreen(
    preferences: ReaderTtsPreferences,
    onBack: () -> Unit,
    onReaderChange: (ReaderTheme, Float, Float, Float, ReaderNavigationMode) -> Unit,
    onAdvancedReaderChange: (Float, ReaderTextAlignment, Float, Boolean) -> Unit,
    onNarrationFlagsChange: (Boolean, Boolean) -> Unit,
    onNarrationSpeedChange: (Float) -> Unit,
    onVoices: () -> Unit,
    onStyles: () -> Unit,
    onStorage: () -> Unit,
    onModelPreparation: () -> Unit,
    onDiagnostics: () -> Unit,
    onAbout: () -> Unit,
) {
    fun updateReader(
        theme: ReaderTheme = preferences.readerTheme,
        fontSize: Float = preferences.fontSizeSp,
        lineSpacing: Float = preferences.lineSpacing,
        margin: Float = preferences.pageMarginDp,
        navigation: ReaderNavigationMode = preferences.navigationMode,
    ) = onReaderChange(theme, fontSize, lineSpacing, margin, navigation)

    fun updateAdvancedReader(
        paragraphSpacing: Float = preferences.paragraphSpacing,
        textAlignment: ReaderTextAlignment = preferences.textAlignment,
        brightness: Float = preferences.screenBrightness,
        keepScreenOn: Boolean = preferences.keepScreenOn,
    ) = onAdvancedReaderChange(paragraphSpacing, textAlignment, brightness, keepScreenOn)

    ScreenScaffold(title = "设置", onBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("阅读设置")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderTheme.entries.forEach { theme ->
                            FilterChip(
                                selected = preferences.readerTheme == theme,
                                onClick = { updateReader(theme = theme) },
                                label = { Text(theme.label()) },
                            )
                        }
                    }
                    SettingStepper("字号", preferences.fontSizeSp, 12f, 48f, 1f) {
                        updateReader(fontSize = it)
                    }
                    SettingStepper("行距", preferences.lineSpacing, 1f, 3f, 0.1f) {
                        updateReader(lineSpacing = it)
                    }
                    SettingStepper("页边距", preferences.pageMarginDp, 0f, 64f, 4f) {
                        updateReader(margin = it)
                    }
                    SettingStepper("段间距", preferences.paragraphSpacing, 0f, 2f, 0.1f) {
                        updateAdvancedReader(paragraphSpacing = it)
                    }
                    Text("文字对齐")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderTextAlignment.entries.forEach { alignment ->
                            FilterChip(
                                selected = preferences.textAlignment == alignment,
                                onClick = { updateAdvancedReader(textAlignment = alignment) },
                                label = { Text(alignment.label()) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderNavigationMode.entries.forEach { mode ->
                            FilterChip(
                                selected = preferences.navigationMode == mode,
                                onClick = { updateReader(navigation = mode) },
                                label = { Text(if (mode == ReaderNavigationMode.SCROLL) "上下滚动" else "分页") },
                            )
                        }
                    }
                    ToggleRow("跟随系统亮度", preferences.screenBrightness < 0f) { useSystem ->
                        updateAdvancedReader(brightness = if (useSystem) -1f else 0.5f)
                    }
                    if (preferences.screenBrightness >= 0f) {
                        SettingStepper(
                            label = "屏幕亮度（%）",
                            value = preferences.screenBrightness * 100f,
                            minimum = 5f,
                            maximum = 100f,
                            step = 5f,
                            decimals = 0,
                        ) { updateAdvancedReader(brightness = it / 100f) }
                    }
                    ToggleRow("阅读时保持屏幕常亮", preferences.keepScreenOn) {
                        updateAdvancedReader(keepScreenOn = it)
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("离线朗读")
                    Button(modifier = Modifier.fillMaxWidth(), onClick = onVoices) {
                        Text("声音选择 · sid ${preferences.defaultVoiceSid}")
                    }
                    Button(modifier = Modifier.fillMaxWidth(), onClick = onStyles) {
                        Text("朗读风格 · ${preferences.narrationStyle.label()}")
                    }
                    SettingStepper("朗读速度", preferences.narrationSpeed, 0.5f, 2f, 0.05f, decimals = 2) {
                        onNarrationSpeedChange(it)
                    }
                    ToggleRow("自动风格规则", preferences.automaticStyle) {
                        onNarrationFlagsChange(it, preferences.automaticFollow)
                    }
                    ToggleRow("当前句自动跟随", preferences.automaticFollow) {
                        onNarrationFlagsChange(preferences.automaticStyle, it)
                    }
                }
            }

            Button(modifier = Modifier.fillMaxWidth(), onClick = onModelPreparation) { Text("模型准备状态") }
            Button(modifier = Modifier.fillMaxWidth(), onClick = onDiagnostics) { Text("离线朗读诊断") }
            Button(modifier = Modifier.fillMaxWidth(), onClick = onStorage) { Text("存储与缓存管理") }
            Button(modifier = Modifier.fillMaxWidth(), onClick = onAbout) { Text("关于与开源许可证") }
        }
    }
}

@Composable
private fun SettingStepper(
    label: String,
    value: Float,
    minimum: Float,
    maximum: Float,
    step: Float,
    decimals: Int = 1,
    onChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label：${"%.${decimals}f".format(value)}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = value > minimum, onClick = { onChange((value - step).coerceAtLeast(minimum)) }) {
                Text("−")
            }
            Button(enabled = value < maximum, onClick = { onChange((value + step).coerceAtMost(maximum)) }) {
                Text("+")
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun ReaderTheme.label(): String = when (this) {
    ReaderTheme.DAY -> "白天"
    ReaderTheme.NIGHT -> "夜间"
    ReaderTheme.EYE_CARE -> "护眼"
}

private fun ReaderTextAlignment.label(): String = when (this) {
    ReaderTextAlignment.PUBLISHER -> "原书"
    ReaderTextAlignment.START -> "起始对齐"
    ReaderTextAlignment.JUSTIFY -> "两端对齐"
}

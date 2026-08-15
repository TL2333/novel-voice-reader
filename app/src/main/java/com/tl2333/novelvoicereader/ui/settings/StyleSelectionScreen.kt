package com.tl2333.novelvoicereader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tl2333.novelvoicereader.tts.tokenizer.NarrationStyle
import com.tl2333.novelvoicereader.tts.tokenizer.NovelStylePlanner
import com.tl2333.novelvoicereader.ui.components.ScreenScaffold

@Composable
fun StyleSelectionScreen(
    selected: NarrationStyle,
    onBack: () -> Unit,
    onSelect: (NarrationStyle) -> Unit,
) {
    ScreenScaffold(title = "朗读风格", onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("风格通过速度、增益、停顿与轻量文本规则实现，不是原生情绪模型或声音克隆。")
            NarrationStyle.entries.forEach { style ->
                val parameters = NovelStylePlanner.parameters(style)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clickable { onSelect(style) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == style, onClick = { onSelect(style) })
                        Column {
                            Text(style.label())
                            Text("速度 ${"%.2f".format(parameters.speed)} · 增益 ${"%.2f".format(parameters.gain)}")
                        }
                    }
                }
            }
        }
    }
}

fun NarrationStyle.label(): String = when (this) {
    NarrationStyle.NEUTRAL -> "中性"
    NarrationStyle.HAPPY -> "轻快"
    NarrationStyle.SAD -> "低沉"
    NarrationStyle.ANGRY -> "强烈"
    NarrationStyle.EXCITED -> "激动"
}

package com.tl2333.novelvoicereader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tl2333.novelvoicereader.tts.kokoro.KokoroVoiceCatalog
import com.tl2333.novelvoicereader.ui.components.ScreenScaffold

@Composable
fun VoiceSelectionScreen(
    selectedSid: Int,
    onBack: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    ScreenScaffold(title = "声音选择", onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "Kokoro 官方中文声音，女声 sid 3–57，男声 sid 58–102。",
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(KokoroVoiceCatalog.voices, key = { it.sid }) { voice ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(voice.sid) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selectedSid == voice.sid, onClick = { onSelect(voice.sid) })
                    Text("${voice.displayName} · sid ${voice.sid} · ${voice.internalName}")
                }
                HorizontalDivider()
            }
        }
    }
}

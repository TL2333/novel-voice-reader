package com.tl2333.novelvoicereader.ui.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tl2333.novelvoicereader.app.StorageSnapshot
import com.tl2333.novelvoicereader.ui.components.ScreenScaffold
import com.tl2333.novelvoicereader.ui.components.formatBytes

@Composable
fun StorageScreen(
    snapshot: StorageSnapshot?,
    cacheLimitBytes: Long,
    busy: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSetCacheLimit: (Long) -> Unit,
    onClearAllNarrationCache: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    ScreenScaffold(title = "存储与缓存管理", onBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("私有 EPUB：${formatBytes(snapshot?.booksBytes)}")
                    Text("真实 Kokoro WAV 缓存：${formatBytes(snapshot?.narrationCacheBytes)}")
                    Text("缓存上限：${formatBytes(cacheLimitBytes)}")
                    Text("设备可用空间：${formatBytes(snapshot?.availableBytes)}")
                }
            }
            Text("缓存上限")
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(256L, 512L, 1_024L).forEach { mebibytes ->
                    Button(
                        enabled = !busy && cacheLimitBytes != mebibytes * 1024 * 1024,
                        onClick = { onSetCacheLimit(mebibytes * 1024 * 1024) },
                    ) {
                        Text("${mebibytes} MB")
                    }
                }
            }
            Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = onRefresh) {
                Text("重新统计")
            }
            Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = onClearAllNarrationCache) {
                Text("清除全部朗读缓存")
            }
            Text("清除朗读缓存不会删除 EPUB、书签或阅读进度。")
            if (busy) CircularProgressIndicator()
        }
    }
}

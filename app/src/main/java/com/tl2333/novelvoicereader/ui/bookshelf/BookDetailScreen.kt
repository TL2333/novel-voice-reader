package com.tl2333.novelvoicereader.ui.bookshelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tl2333.novelvoicereader.data.database.BookEntity
import com.tl2333.novelvoicereader.data.database.ReadingProgressEntity
import com.tl2333.novelvoicereader.ui.components.ScreenScaffold
import com.tl2333.novelvoicereader.ui.components.formatBytes
import java.io.File

@Composable
fun BookDetailScreen(
    book: BookEntity?,
    progress: ReadingProgressEntity?,
    busy: Boolean,
    onBack: () -> Unit,
    onRead: (BookEntity) -> Unit,
    onBookmarks: (BookEntity) -> Unit,
    onClearCache: (BookEntity) -> Unit,
    onDelete: (BookEntity) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    ScreenScaffold(title = book?.title ?: "书籍详情", onBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (book == null) {
                Text("这本书已不存在。")
                return@Column
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(book.title)
                    Text("作者：${book.author ?: "未知"}")
                    Text("格式：${listOfNotNull(book.sourceType, book.sourceDetail).joinToString(" · ")}")
                    Text("文档：${formatBytes(File(book.epubPath).takeIf(File::isFile)?.length())}")
                    Text("阅读进度：${"%.1f".format((progress?.totalProgression ?: 0.0) * 100)}%")
                }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && File(book.epubPath).isFile,
                onClick = { onRead(book) },
            ) { Text(if (File(book.epubPath).isFile) "打开阅读" else "文档文件缺失") }
            Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { onBookmarks(book) }) {
                Text("书签列表")
            }
            Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { onClearCache(book) }) {
                Text("清除本书朗读缓存")
            }
            Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { confirmDelete = true }) {
                Text("删除书籍")
            }
        }
    }

    if (confirmDelete && book != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除《${book.title}》？") },
            text = { Text("将删除应用私有文档与本书朗读缓存，同时清除关联进度和书签。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(book)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

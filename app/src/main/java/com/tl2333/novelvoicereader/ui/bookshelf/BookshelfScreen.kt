package com.tl2333.novelvoicereader.ui.bookshelf

import android.net.Uri
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.tl2333.novelvoicereader.data.database.BookEntity
import com.tl2333.novelvoicereader.ui.components.ScreenScaffold
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun BookshelfScreen(
    books: List<BookEntity>,
    progressByBookId: Map<String, Double>,
    importing: Boolean,
    onImportDocument: (Uri) -> Unit,
    onImportBuiltIn: () -> Unit,
    onOpenBook: (BookEntity) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportDocument)
    }
    val visibleBooks = remember(books, search) {
        val query = search.trim()
        if (query.isEmpty()) books else books.filter {
            it.title.contains(query, ignoreCase = true) || it.author.orEmpty().contains(query, ignoreCase = true)
        }
    }

    ScreenScaffold(
        title = "AI 小说阅读器",
        actions = { TextButton(onClick = onOpenSettings) { Text("设置") } },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索书名或作者") },
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !importing,
                    onClick = {
                        launcher.launch(
                            arrayOf(
                                "application/epub+zip",
                                "application/zip",
                                "application/octet-stream",
                                "*/*",
                            ),
                        )
                    },
                ) { Text("导入 EPUB") }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !importing,
                    onClick = onImportBuiltIn,
                ) { Text("导入内置测试书") }
                if (importing) CircularProgressIndicator(Modifier.size(24.dp))
            }
            if (visibleBooks.isEmpty()) {
                Text(if (search.isBlank()) "书架为空。请导入 EPUB 或内置测试书。" else "没有匹配的书籍。")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visibleBooks, key = BookEntity::id) { book ->
                        val progression = progressByBookId[book.id]?.coerceIn(0.0, 1.0) ?: 0.0
                        Card(onClick = { onOpenBook(book) }) {
                            ListItem(
                                headlineContent = { Text(book.title) },
                                supportingContent = {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(book.author ?: "未知作者")
                                        Text("阅读进度 ${"%.1f".format(progression * 100)}%")
                                        LinearProgressIndicator(
                                            progress = { progression.toFloat() },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Text(
                                            book.lastReadAt?.let { "最近阅读 ${formatTimestamp(it)}" }
                                                ?: "尚未开始阅读",
                                        )
                                    }
                                },
                                leadingContent = { BookCover(book) },
                                trailingContent = { Text("详情") },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookCover(book: BookEntity) {
    val bitmap = remember(book.coverPath) {
        book.coverPath
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "《${book.title}》封面",
            modifier = Modifier.size(width = 48.dp, height = 64.dp),
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(
            modifier = Modifier.size(width = 48.dp, height = 64.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(book.title.trim().firstOrNull()?.toString() ?: "书")
            }
        }
    }
}

private fun formatTimestamp(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))

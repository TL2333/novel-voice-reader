package com.tl2333.novelvoicereader.ui.bookmarks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tl2333.novelvoicereader.data.database.BookEntity
import com.tl2333.novelvoicereader.data.database.BookmarkEntity
import com.tl2333.novelvoicereader.data.database.LocatorJson
import com.tl2333.novelvoicereader.ui.components.ScreenScaffold
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun BookmarksScreen(
    book: BookEntity?,
    bookmarks: List<BookmarkEntity>,
    onBack: () -> Unit,
    onOpen: (BookmarkEntity) -> Unit,
    onDelete: (BookmarkEntity) -> Unit,
) {
    ScreenScaffold(title = book?.let { "《${it.title}》书签" } ?: "书签", onBack = onBack) { padding ->
        if (bookmarks.isEmpty()) {
            Text("尚未添加书签。请在阅读页使用“添加书签”。", Modifier.padding(padding).padding(16.dp))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(bookmarks, key = BookmarkEntity::id) { bookmark ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(bookmark.label ?: "未命名书签")
                            Text(locatorHref(bookmark.locatorJson))
                            Row {
                                TextButton(
                                    enabled = book != null,
                                    onClick = { onOpen(bookmark) },
                                ) { Text("从此处阅读") }
                                TextButton(onClick = { onDelete(bookmark) }) { Text("删除书签") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun locatorHref(locatorJson: String): String = runCatching {
    LocatorJson.parse(locatorJson)["href"]?.jsonPrimitive?.content
}.getOrNull()?.takeIf(String::isNotBlank) ?: "位置数据不可读"

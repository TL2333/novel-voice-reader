package com.tl2333.novelvoicereader.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.tl2333.novelvoicereader.NovelVoiceApplication
import com.tl2333.novelvoicereader.content.model.BlockType
import com.tl2333.novelvoicereader.content.model.CanonicalDocument
import com.tl2333.novelvoicereader.content.model.CanonicalDocumentCodec
import com.tl2333.novelvoicereader.content.model.DocumentLocationJson
import com.tl2333.novelvoicereader.data.database.BookEntity
import com.tl2333.novelvoicereader.data.preferences.ReaderTheme
import com.tl2333.novelvoicereader.data.preferences.ReaderTtsPreferences
import com.tl2333.novelvoicereader.narration.CanonicalNarrationSession
import com.tl2333.novelvoicereader.narration.NarrationState
import com.tl2333.novelvoicereader.ui.theme.NovelVoiceTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GenericDocumentReaderActivity : ComponentActivity() {
    private var narrationSession: CanonicalNarrationSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run { finish(); return }
        val container = (application as NovelVoiceApplication).container
        lifecycleScope.launch {
            val book = withContext(Dispatchers.IO) { container.books.get(bookId) }
            val canonicalPath = book?.canonicalDocumentPath
            val document = canonicalPath?.let(::File)?.takeIf(File::isFile)?.let {
                withContext(Dispatchers.IO) { CanonicalDocumentCodec.read(it) }
            }
            if (book == null || document == null) {
                finish()
                return@launch
            }
            val preferences = container.preferences.preferences.first()
            val session = CanonicalNarrationSession(
                this@GenericDocumentReaderActivity,
                document,
                preferences.defaultVoiceSid,
                preferences.narrationSpeed.coerceToSupportedSpeed(),
            )
            narrationSession = session
            container.books.markRead(book.id)
            persistNarrationProgress(book, document, session)
            val initialLocation = intent.getStringExtra(EXTRA_LOCATION)?.let { runCatching { DocumentLocationJson.decode(it) }.getOrNull() }
                ?: container.readingProgress.observe(book.id).first()?.locatorJson?.let { runCatching { DocumentLocationJson.decode(it) }.getOrNull() }
            setContent {
                NovelVoiceTheme {
                    GenericDocumentReader(
                        book,
                        document,
                        session,
                        preferences,
                        initialBlockId = initialLocation?.blockId,
                        onBlockSelected = { blockId ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val index = document.blocks.indexOfFirst { it.id == blockId }.takeIf { it >= 0 } ?: return@launch
                                val total = if (document.blocks.size <= 1) 0.0 else index.toDouble() / (document.blocks.size - 1)
                                container.readingProgress.saveDocumentLocation(book.id, DocumentLocationJson.encode(document.blocks[index].anchor), total)
                            }
                        },
                        onBookmark = { blockId ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val block = document.blocks.firstOrNull { it.id == blockId } ?: return@launch
                                container.bookmarks.saveDocumentLocation(
                                    book.id,
                                    DocumentLocationJson.encode(block.anchor),
                                    block.text.take(48),
                                )
                            }
                        },
                        onBack = ::finish,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        narrationSession?.close()
        narrationSession = null
        super.onDestroy()
    }

    private fun persistNarrationProgress(book: BookEntity, document: CanonicalDocument, session: CanonicalNarrationSession) {
        val container = (application as NovelVoiceApplication).container
        lifecycleScope.launch(Dispatchers.IO) {
            session.state
                .distinctUntilChangedBy { it.currentSegment?.id }
                .collect { state ->
                    val segment = state.currentSegment ?: return@collect
                    val blockIndex = document.blocks.indexOfFirst { it.id == segment.blockId }.coerceAtLeast(0)
                    val total = if (document.blocks.size <= 1) 0.0 else blockIndex.toDouble() / (document.blocks.size - 1)
                    container.readingProgress.saveDocumentLocation(
                        book.id,
                        DocumentLocationJson.encode(segment.anchor),
                        total,
                    )
                }
        }
    }

    companion object {
        private const val EXTRA_BOOK_ID = "bookId"
        private const val EXTRA_LOCATION = "location"
        fun createIntent(context: Context, bookId: String, initialLocationJson: String? = null): Intent =
            Intent(context, GenericDocumentReaderActivity::class.java)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_LOCATION, initialLocationJson)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenericDocumentReader(
    book: BookEntity,
    document: CanonicalDocument,
    session: CanonicalNarrationSession,
    preferences: ReaderTtsPreferences,
    initialBlockId: String?,
    onBlockSelected: (String) -> Unit,
    onBookmark: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by session.state.collectAsState()
    val listState = rememberLazyListState()
    var selectedBlockId by remember { mutableStateOf(initialBlockId ?: document.blocks.firstOrNull()?.id) }
    val activeBlockId = state.currentSegment?.blockId
    LaunchedEffect(activeBlockId) {
        val index = document.blocks.indexOfFirst { it.id == activeBlockId }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    LaunchedEffect(initialBlockId) {
        val index = document.blocks.indexOfFirst { it.id == initialBlockId }
        if (index >= 0) listState.scrollToItem(index)
    }
    DisposableEffect(session) { onDispose { session.stop() } }
    val readerBackground = when (preferences.readerTheme) {
        ReaderTheme.DAY -> Color(0xFFFFFBFE)
        ReaderTheme.NIGHT -> Color(0xFF161616)
        ReaderTheme.EYE_CARE -> Color(0xFFF4ECD8)
    }
    val readerForeground = if (preferences.readerTheme == ReaderTheme.NIGHT) Color(0xFFE8E8E8) else Color(0xFF252525)

    Scaffold(
        containerColor = readerBackground,
        topBar = { TopAppBar(title = { Text(book.title) }, navigationIcon = { Button(onClick = onBack) { Text("返回") } }) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.message ?: state.narration.state.name)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { selectedBlockId?.let(session::startAtBlock) }) { Text("从此处朗读") }
                    Button(onClick = { selectedBlockId?.let(onBookmark) }) { Text("书签") }
                    Button(onClick = session::pauseOrResume, enabled = state.narration.state in setOf(NarrationState.PLAYING, NarrationState.PAUSED, NarrationState.BUFFERING)) {
                        Text(if (state.narration.state == NarrationState.PAUSED) "继续" else "暂停")
                    }
                    Button(onClick = session::stop) { Text("停止") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1f, 1.5f, 2f).forEach { speed ->
                        Button(onClick = { session.setPlaybackSpeed(speed) }) { Text("${speed}x") }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState) {
            itemsIndexed(document.blocks, key = { _, block -> block.id }) { _, block ->
                if (block.type != BlockType.PAGE_BREAK) {
                    Text(
                        text = block.text,
                        color = readerForeground,
                        style = (if (block.type in setOf(BlockType.TITLE, BlockType.HEADING)) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge).copy(
                            fontSize = preferences.fontSizeSp.sp,
                            lineHeight = (preferences.fontSizeSp * preferences.lineSpacing).sp,
                        ),
                        modifier = Modifier.fillMaxWidth()
                            .background(if (block.id == activeBlockId) Color(0x33FFC107) else Color.Transparent)
                            .clickable {
                                selectedBlockId = block.id
                                onBlockSelected(block.id)
                            }
                            .padding(horizontal = preferences.pageMarginDp.dp, vertical = (10 + preferences.paragraphSpacing * 4).dp),
                    )
                }
            }
        }
    }
}

private fun Float.coerceToSupportedSpeed(): Float =
    listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).minBy { kotlin.math.abs(it - this) }

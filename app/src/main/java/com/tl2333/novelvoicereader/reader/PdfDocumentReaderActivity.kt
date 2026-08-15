package com.tl2333.novelvoicereader.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.tl2333.novelvoicereader.NovelVoiceApplication
import com.tl2333.novelvoicereader.content.model.CanonicalDocument
import com.tl2333.novelvoicereader.content.model.CanonicalDocumentCodec
import com.tl2333.novelvoicereader.content.model.DocumentLocationJson
import com.tl2333.novelvoicereader.content.pdf.PdfPageRenderer
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

class PdfDocumentReaderActivity : ComponentActivity() {
    private var narrationSession: CanonicalNarrationSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run { finish(); return }
        val container = (application as NovelVoiceApplication).container
        lifecycleScope.launch {
            val book = withContext(Dispatchers.IO) { container.books.get(bookId) }
            val document = book?.canonicalDocumentPath?.let(::File)?.takeIf(File::isFile)?.let {
                withContext(Dispatchers.IO) { CanonicalDocumentCodec.read(it) }
            }
            if (book == null || document == null || !File(book.epubPath).isFile) {
                finish()
                return@launch
            }
            val preferences = container.preferences.preferences.first()
            val session = CanonicalNarrationSession(this@PdfDocumentReaderActivity, document, preferences.defaultVoiceSid, preferences.narrationSpeed.coerceToSupportedPdfSpeed())
            narrationSession = session
            container.books.markRead(book.id)
            persistNarrationProgress(book, document, session)
            val initialLocation = intent.getStringExtra(EXTRA_LOCATION)?.let { runCatching { DocumentLocationJson.decode(it) }.getOrNull() }
                ?: container.readingProgress.observe(book.id).first()?.locatorJson?.let { runCatching { DocumentLocationJson.decode(it) }.getOrNull() }
            setContent {
                NovelVoiceTheme {
                    PdfDocumentReader(
                        book = book,
                        document = document,
                        renderer = remember { PdfPageRenderer(File(book.epubPath)) },
                        initialPage = initialLocation?.page ?: 1,
                        session = session,
                        preferences = preferences,
                        onPageSelected = { page ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val block = document.blocks.firstOrNull { it.anchor.page == page } ?: return@launch
                                val total = if (document.metadata["pageCount"]?.toIntOrNull()?.let { it > 1 } == true) {
                                    (page - 1).toDouble() / (document.metadata.getValue("pageCount").toInt() - 1)
                                } else 0.0
                                container.readingProgress.saveDocumentLocation(book.id, DocumentLocationJson.encode(block.anchor), total)
                            }
                        },
                        onBookmark = { page ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val block = document.blocks.firstOrNull { it.anchor.page == page } ?: return@launch
                                container.bookmarks.saveDocumentLocation(
                                    book.id,
                                    DocumentLocationJson.encode(block.anchor),
                                    "第 $page 页",
                                )
                            }
                        },
                        onSpeedSelected = { speed ->
                            session.setPlaybackSpeed(speed)
                            lifecycleScope.launch(Dispatchers.IO) {
                                container.preferences.updateNarration(
                                    preferences.defaultVoiceSid,
                                    speed,
                                    preferences.narrationStyle,
                                    preferences.automaticStyle,
                                    preferences.automaticFollow,
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
            session.state.distinctUntilChangedBy { it.currentSegment?.id }.collect { state ->
                val segment = state.currentSegment ?: return@collect
                val blockIndex = document.blocks.indexOfFirst { it.id == segment.blockId }.coerceAtLeast(0)
                val total = if (document.blocks.size <= 1) 0.0 else blockIndex.toDouble() / (document.blocks.size - 1)
                container.readingProgress.saveDocumentLocation(book.id, DocumentLocationJson.encode(segment.anchor), total)
            }
        }
    }

    companion object {
        private const val EXTRA_BOOK_ID = "bookId"
        private const val EXTRA_LOCATION = "location"

        fun createIntent(context: Context, bookId: String, initialLocationJson: String? = null): Intent =
            Intent(context, PdfDocumentReaderActivity::class.java)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_LOCATION, initialLocationJson)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfDocumentReader(
    book: BookEntity,
    document: CanonicalDocument,
    renderer: PdfPageRenderer,
    initialPage: Int,
    session: CanonicalNarrationSession,
    preferences: ReaderTtsPreferences,
    onPageSelected: (Int) -> Unit,
    onBookmark: (Int) -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onBack: () -> Unit,
) {
    val state by session.state.collectAsState()
    val pageCount = document.metadata["pageCount"]?.toIntOrNull() ?: renderer.pageCount()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (initialPage - 1).coerceIn(0, pageCount - 1))
    var selectedPage by remember { mutableIntStateOf(initialPage.coerceIn(1, pageCount)) }
    val activePage = state.currentSegment?.anchor?.page
    LaunchedEffect(activePage) {
        activePage?.takeIf { it in 1..pageCount }?.let {
            selectedPage = it
            listState.animateScrollToItem(it - 1)
        }
    }
    DisposableEffect(session) { onDispose { session.stop() } }
    val readerBackground = when (preferences.readerTheme) {
        ReaderTheme.DAY -> androidx.compose.ui.graphics.Color(0xFFFFFBFE)
        ReaderTheme.NIGHT -> androidx.compose.ui.graphics.Color(0xFF161616)
        ReaderTheme.EYE_CARE -> androidx.compose.ui.graphics.Color(0xFFF4ECD8)
    }

    Scaffold(
        containerColor = readerBackground,
        topBar = { TopAppBar(title = { Text(book.title) }, navigationIcon = { Button(onClick = onBack) { Text("返回") } }) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("第 $selectedPage / $pageCount 页 · ${state.message ?: state.narration.state.name}")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { document.blocks.firstOrNull { it.anchor.page == selectedPage }?.id?.let(session::startAtBlock) }) { Text("从本页朗读") }
                    Button(onClick = { onBookmark(selectedPage) }) { Text("书签") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = session::previous) { Text("上一句") }
                    Button(onClick = session::pauseOrResume, enabled = state.narration.state in setOf(NarrationState.PLAYING, NarrationState.PAUSED, NarrationState.BUFFERING)) {
                        Text(if (state.narration.state == NarrationState.PAUSED) "继续" else "暂停")
                    }
                    Button(onClick = session::next) { Text("下一句") }
                    Button(onClick = session::stop) { Text("停止") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1f, 1.5f, 2f).forEach { speed -> Button(onClick = { onSpeedSelected(speed) }) { Text("${speed}x") } }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items((1..pageCount).toList(), key = { it }) { page ->
                PdfPage(renderer, page, selected = page == selectedPage) {
                    selectedPage = page
                    onPageSelected(page)
                }
            }
        }
    }
}

@Composable
private fun PdfPage(renderer: PdfPageRenderer, page: Int, selected: Boolean, onSelect: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, renderer, page) {
        value = withContext(Dispatchers.IO) { renderer.render(page, 1080) }
    }
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onSelect)
            .padding(6.dp),
    ) {
        Text("第 $page 页", style = MaterialTheme.typography.labelMedium)
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = "PDF 第 $page 页", modifier = Modifier.fillMaxWidth()) }
            ?: Text("正在渲染…", modifier = Modifier.padding(24.dp))
    }
}

private fun Float.coerceToSupportedPdfSpeed(): Float =
    listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).minBy { kotlin.math.abs(it - this) }

package com.tl2333.novelvoicereader.app

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tl2333.novelvoicereader.BuildConfig
import com.tl2333.novelvoicereader.data.database.BookEntity
import com.tl2333.novelvoicereader.data.preferences.ReaderTextAlignment
import com.tl2333.novelvoicereader.data.preferences.ReaderTtsPreferences
import com.tl2333.novelvoicereader.reader.ReaderActivity
import com.tl2333.novelvoicereader.reader.GenericDocumentReaderActivity
import com.tl2333.novelvoicereader.reader.PdfDocumentReaderActivity
import com.tl2333.novelvoicereader.tts.tokenizer.NarrationStyle
import com.tl2333.novelvoicereader.ui.about.AboutScreen
import com.tl2333.novelvoicereader.ui.bookmarks.BookmarksScreen
import com.tl2333.novelvoicereader.ui.bookshelf.BookDetailScreen
import com.tl2333.novelvoicereader.ui.bookshelf.BookshelfScreen
import com.tl2333.novelvoicereader.ui.diagnostics.DiagnosticsActionResult
import com.tl2333.novelvoicereader.ui.diagnostics.DiagnosticsScreen
import com.tl2333.novelvoicereader.ui.diagnostics.ModelIntegrity
import com.tl2333.novelvoicereader.ui.diagnostics.ModelPreparationScreen
import com.tl2333.novelvoicereader.ui.settings.SettingsScreen
import com.tl2333.novelvoicereader.ui.settings.StyleSelectionScreen
import com.tl2333.novelvoicereader.ui.settings.VoiceSelectionScreen
import com.tl2333.novelvoicereader.ui.storage.StorageScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private object Routes {
    const val BOOKSHELF = "bookshelf"
    const val BOOK_DETAIL = "book/{bookId}"
    const val BOOKMARKS = "book/{bookId}/bookmarks"
    const val SETTINGS = "settings"
    const val VOICES = "settings/voices"
    const val STYLES = "settings/styles"
    const val STORAGE = "settings/storage"
    const val MODEL = "settings/model"
    const val DIAGNOSTICS = "settings/diagnostics"
    const val ABOUT = "settings/about"

    fun book(bookId: String) = "book/$bookId"
    fun bookmarks(bookId: String) = "book/$bookId/bookmarks"
}

@Composable
fun AppNavigation(container: AppContainer) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val exportTrace = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = container.exportNarrationTraces(uri)
                snackbar.showSnackbar(result.message)
            }
        }
    }
    val books by container.books.observeAll().collectAsState(initial = emptyList())
    val progressRows by container.readingProgress.observeAll().collectAsState(initial = emptyList())
    val preferences by container.preferences.preferences.collectAsState(initial = ReaderTtsPreferences())
    val diagnostics by container.diagnosticsController.state.collectAsState()
    var libraryBusy by remember { mutableStateOf(false) }
    var storageBusy by remember { mutableStateOf(false) }
    var storage by remember { mutableStateOf<StorageSnapshot?>(null) }
    val startupState = remember(context) {
        context.getSharedPreferences(STARTUP_STATE_FILE, Context.MODE_PRIVATE)
    }
    val modelPreparedKey = remember { "$MODEL_PREPARED_KEY_PREFIX:${BuildConfig.KOKORO_COMMIT}" }
    var needsStartupModelPreparation by remember {
        mutableStateOf(!startupState.getBoolean(modelPreparedKey, false))
    }

    fun runLibraryAction(
        action: suspend () -> LibraryActionResult,
        afterSuccess: (LibraryActionResult) -> Unit = {},
    ) {
        if (libraryBusy) return
        libraryBusy = true
        scope.launch {
            val result = try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LibraryActionResult(false, "操作失败：${error.message.orEmpty()}")
            } finally {
                libraryBusy = false
            }
            if (result.successful) afterSuccess(result)
            snackbar.showSnackbar(result.message)
        }
    }

    fun updateReader(
        theme: com.tl2333.novelvoicereader.data.preferences.ReaderTheme,
        fontSize: Float,
        lineSpacing: Float,
        margin: Float,
        navigation: com.tl2333.novelvoicereader.data.preferences.ReaderNavigationMode,
    ) {
        scope.launch {
            runCatching { container.preferences.updateReader(theme, fontSize, lineSpacing, margin, navigation) }
                .onFailure { snackbar.showSnackbar("阅读设置保存失败：${it.message.orEmpty()}") }
        }
    }

    fun updateNarration(
        voiceSid: Int = preferences.defaultVoiceSid,
        speed: Float = preferences.narrationSpeed,
        style: NarrationStyle = preferences.narrationStyle,
        automaticStyle: Boolean = preferences.automaticStyle,
        automaticFollow: Boolean = preferences.automaticFollow,
    ) {
        scope.launch {
            runCatching {
                container.preferences.updateNarration(
                    voiceSid = voiceSid,
                    speed = speed,
                    style = style,
                    automaticStyle = automaticStyle,
                    automaticFollow = automaticFollow,
                )
            }.onFailure { snackbar.showSnackbar("朗读设置保存失败：${it.message.orEmpty()}") }
        }
    }

    fun updateAdvancedReader(
        paragraphSpacing: Float,
        textAlignment: ReaderTextAlignment,
        screenBrightness: Float,
        keepScreenOn: Boolean,
    ) {
        scope.launch {
            runCatching {
                container.preferences.updateAdvancedReader(
                    paragraphSpacing = paragraphSpacing,
                    textAlignment = textAlignment,
                    screenBrightness = screenBrightness,
                    keepScreenOn = keepScreenOn,
                )
            }.onFailure { snackbar.showSnackbar("阅读设置保存失败：${it.message.orEmpty()}") }
        }
    }

    fun diagnosticsAction(action: suspend () -> DiagnosticsActionResult) {
        scope.launch {
            val result = runCatching { action() }.getOrElse {
                DiagnosticsActionResult.Rejected("DIAGNOSTIC_ACTION_FAILED", it.message ?: "诊断操作失败")
            }
            snackbar.showSnackbar(
                when (result) {
                    is DiagnosticsActionResult.Completed -> result.message
                    is DiagnosticsActionResult.Rejected -> "${result.errorCode}：${result.message}"
                },
            )
        }
    }

    LaunchedEffect(needsStartupModelPreparation) {
        if (needsStartupModelPreparation) {
            navController.navigate(Routes.MODEL) { launchSingleTop = true }
        }
    }
    LaunchedEffect(diagnostics.modelIntegrity, diagnostics.initializationMillis) {
        if (
            diagnostics.modelIntegrity == ModelIntegrity.VALID &&
            diagnostics.initializationMillis != null
        ) {
            startupState.edit().putBoolean(modelPreparedKey, true).apply()
            needsStartupModelPreparation = false
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { outerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.BOOKSHELF,
            modifier = Modifier.padding(outerPadding),
        ) {
            composable(Routes.BOOKSHELF) {
                BookshelfScreen(
                    books = books,
                    progressByBookId = progressRows.associate { it.bookId to it.totalProgression },
                    importing = libraryBusy,
                    onImportDocument = { uri ->
                        runLibraryAction(action = { container.importSafDocument(uri) })
                    },
                    onImportBuiltIn = {
                        runLibraryAction(action = { container.importBuiltInTestBook() })
                    },
                    onImportUrl = { url ->
                        runLibraryAction(action = { container.importWebUrl(url) })
                    },
                    onOpenBook = { navController.navigate(Routes.book(it.id)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(
                route = Routes.BOOK_DETAIL,
                arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
            ) { entry ->
                val bookId = requireNotNull(entry.arguments?.getString("bookId"))
                val book = books.firstOrNull { it.id == bookId }
                val progress by container.readingProgress.observe(bookId).collectAsState(initial = null)
                BookDetailScreen(
                    book = book,
                    progress = progress,
                    busy = libraryBusy,
                    onBack = { navController.navigateUp() },
                    onRead = { launchReader(context, it) },
                    onBookmarks = { navController.navigate(Routes.bookmarks(it.id)) },
                    onClearCache = { selected ->
                        runLibraryAction(action = { container.clearBookCache(selected.id) })
                    },
                    onDelete = { selected ->
                        runLibraryAction(
                            action = { container.deleteBook(selected.id) },
                            afterSuccess = { navController.navigateUp() },
                        )
                    },
                )
            }
            composable(
                route = Routes.BOOKMARKS,
                arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
            ) { entry ->
                val bookId = requireNotNull(entry.arguments?.getString("bookId"))
                val bookmarks by container.bookmarks.observeForBook(bookId).collectAsState(initial = emptyList())
                BookmarksScreen(
                    book = books.firstOrNull { it.id == bookId },
                    bookmarks = bookmarks,
                    onBack = { navController.navigateUp() },
                    onOpen = { bookmark ->
                        books.firstOrNull { it.id == bookId }?.let { book ->
                            launchReader(context, book, bookmark.locatorJson)
                        }
                    },
                    onDelete = { bookmark ->
                        scope.launch {
                            runCatching { container.bookmarks.delete(bookmark.id) }
                                .onSuccess { snackbar.showSnackbar("已删除书签。") }
                                .onFailure { snackbar.showSnackbar("书签删除失败：${it.message.orEmpty()}") }
                        }
                    },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    preferences = preferences,
                    onBack = { navController.navigateUp() },
                    onReaderChange = ::updateReader,
                    onAdvancedReaderChange = ::updateAdvancedReader,
                    onNarrationFlagsChange = { automaticStyle, automaticFollow ->
                        updateNarration(automaticStyle = automaticStyle, automaticFollow = automaticFollow)
                    },
                    onNarrationSpeedChange = { updateNarration(speed = it) },
                    onVoices = { navController.navigate(Routes.VOICES) },
                    onStyles = { navController.navigate(Routes.STYLES) },
                    onStorage = { navController.navigate(Routes.STORAGE) },
                    onModelPreparation = { navController.navigate(Routes.MODEL) },
                    onDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onAbout = { navController.navigate(Routes.ABOUT) },
                )
            }
            composable(Routes.VOICES) {
                VoiceSelectionScreen(
                    selectedSid = preferences.defaultVoiceSid,
                    onBack = { navController.navigateUp() },
                    onSelect = { updateNarration(voiceSid = it) },
                )
            }
            composable(Routes.STYLES) {
                StyleSelectionScreen(
                    selected = preferences.narrationStyle,
                    onBack = { navController.navigateUp() },
                    onSelect = { updateNarration(style = it) },
                )
            }
            composable(Routes.STORAGE) {
                StorageScreen(
                    snapshot = storage,
                    cacheLimitBytes = preferences.cacheLimitBytes.takeIf { it > 0L }
                        ?: storage?.narrationCacheLimitBytes
                        ?: 0L,
                    busy = storageBusy,
                    onBack = { navController.navigateUp() },
                    onRefresh = {
                        if (!storageBusy) {
                            storageBusy = true
                            scope.launch {
                                try {
                                    storage = container.storageSnapshot()
                                } catch (error: Exception) {
                                    snackbar.showSnackbar("存储统计失败：${error.message.orEmpty()}")
                                } finally {
                                    storageBusy = false
                                }
                            }
                        }
                    },
                    onSetCacheLimit = { bytes ->
                        scope.launch {
                            runCatching { container.preferences.setCacheLimit(bytes) }
                                .onSuccess { snackbar.showSnackbar("缓存上限已更新；新朗读任务将使用此上限。") }
                                .onFailure { snackbar.showSnackbar("缓存上限保存失败：${it.message.orEmpty()}") }
                        }
                    },
                    onClearAllNarrationCache = {
                        if (!storageBusy) {
                            storageBusy = true
                            scope.launch {
                                try {
                                    val result = container.clearAllNarrationCache()
                                    storage = container.storageSnapshot()
                                    snackbar.showSnackbar(result.message)
                                } catch (error: Exception) {
                                    snackbar.showSnackbar("缓存清理失败：${error.message.orEmpty()}")
                                } finally {
                                    storageBusy = false
                                }
                            }
                        }
                    },
                )
            }
            composable(Routes.MODEL) {
                ModelPreparationScreen(
                    state = diagnostics,
                    prepareOnEnter = needsStartupModelPreparation,
                    onBack = { navController.navigateUp() },
                    onRefresh = { diagnosticsAction(container.diagnosticsController::refresh) },
                    onPrepare = { diagnosticsAction(container.diagnosticsController::prepareModel) },
                )
            }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(
                    state = diagnostics,
                    onBack = { navController.navigateUp() },
                    onRefresh = { diagnosticsAction(container.diagnosticsController::refresh) },
                    onSelectVoice = { navController.navigate(Routes.VOICES) },
                    onGenerateTestSpeech = { diagnosticsAction(container.diagnosticsController::generateTestSpeech) },
                    onStopAudio = container.diagnosticsController::stopAudio,
                    onExportTrace = { exportTrace.launch("novel-voice-narration-trace.jsonl") },
                    onBenchmark = {
                        scope.launch {
                            snackbar.showSnackbar(container.runDeviceTtsBenchmark().message)
                        }
                    },
                )
            }
            composable(Routes.ABOUT) { AboutScreen(onBack = { navController.navigateUp() }) }
        }
    }
}

private const val STARTUP_STATE_FILE = "startup-state"
private const val MODEL_PREPARED_KEY_PREFIX = "kokoro-model-prepared"

private fun launchReader(
    context: Context,
    book: BookEntity,
    initialLocatorJson: String? = null,
) {
    if (book.sourceType == "PDF") {
        context.startActivity(PdfDocumentReaderActivity.createIntent(context, book.id, initialLocatorJson))
        return
    }
    if (book.sourceType != "EPUB") {
        context.startActivity(GenericDocumentReaderActivity.createIntent(context, book.id, initialLocatorJson))
        return
    }
    context.startActivity(
        ReaderActivity.createIntent(
            context = context,
            bookId = book.id,
            privateEpubPath = book.epubPath,
            initialLocatorJson = initialLocatorJson,
        ),
    )
}

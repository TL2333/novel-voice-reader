@file:OptIn(
    kotlinx.coroutines.FlowPreview::class,
    org.readium.r2.shared.ExperimentalReadiumApi::class,
)

package com.tl2333.novelvoicereader.reader

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tl2333.novelvoicereader.data.preferences.ReaderNavigationMode
import com.tl2333.novelvoicereader.data.preferences.ReaderTextAlignment
import com.tl2333.novelvoicereader.data.preferences.ReaderTheme
import com.tl2333.novelvoicereader.data.preferences.ReaderTtsPreferences
import com.tl2333.novelvoicereader.tts.tokenizer.NarrationStyle
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.flatten

data class ReaderRequest(
    val bookId: String,
    val privateEpubPath: String? = null,
    /** One-shot launch location, e.g. a bookmark. It is not written to progress directly. */
    val initialLocatorJson: String? = null,
)

data class ReaderSession(
    val request: ReaderRequest,
    val title: String,
    val publication: Publication,
    val navigatorFactory: EpubNavigatorFactory,
    val initialLocator: Locator?,
    val initialPreferences: EpubPreferences,
    val chapters: List<Link>,
)

sealed interface ReaderLoadState {
    data object Loading : ReaderLoadState
    data class Ready(val session: ReaderSession) : ReaderLoadState
    data class Error(val message: String) : ReaderLoadState
}

data class ChapterNavigationState(
    val title: String = "",
    val canGoPrevious: Boolean = false,
    val canGoNext: Boolean = false,
)

/** Coordinates the visual EPUB navigator, durable locators, and the independent TTS navigator. */
class ReaderViewModel(
    private val application: Application,
    private val dependencies: ReaderDependencies,
) : ViewModel() {

    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mutableLoadState = MutableStateFlow<ReaderLoadState>(ReaderLoadState.Loading)
    private val mutablePreferences = MutableStateFlow(ReaderTtsPreferences())
    private val mutableEpubPreferences = MutableStateFlow(
        ReaderTtsPreferences().toEpubPreferences(),
    )
    private val mutableCurrentLocator = MutableStateFlow<Locator?>(null)
    private val mutableChapter = MutableStateFlow(ChapterNavigationState())
    private val mutableNarration = MutableStateFlow(
        TtsVisualSynchronizer.State(available = dependencies.narrationFactory != null),
    )
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val loadState: StateFlow<ReaderLoadState> = mutableLoadState.asStateFlow()
    val preferences: StateFlow<ReaderTtsPreferences> = mutablePreferences.asStateFlow()
    val epubPreferences: StateFlow<EpubPreferences> = mutableEpubPreferences.asStateFlow()
    val currentLocator: StateFlow<Locator?> = mutableCurrentLocator.asStateFlow()
    val chapter: StateFlow<ChapterNavigationState> = mutableChapter.asStateFlow()
    val narration: StateFlow<TtsVisualSynchronizer.State> = mutableNarration.asStateFlow()
    val messages = mutableMessages.asSharedFlow()

    private var openedRequest: ReaderRequest? = null
    private var loadJob: Job? = null
    private var progressJob: Job? = null
    private var narrationStateJob: Job? = null
    private var visualNavigator: EpubNavigatorFragment? = null
    private var synchronizer: TtsVisualSynchronizer? = null
    private var chapterCursor: Int? = null
    private var currentSession: ReaderSession? = null
    /** Latest progress event from either the visual or TTS Readium navigator. */
    private var latestProgressLocator: Locator? = null

    init {
        dependencies.preferencesStore.preferences
            .onEach { preference ->
                mutablePreferences.value = preference
                mutableEpubPreferences.value = preference.toEpubPreferences()
                visualNavigator?.submitPreferences(mutableEpubPreferences.value)
            }
            .catch { error ->
                mutableMessages.emit("阅读设置加载失败：${error.message.orEmpty()}")
            }
            .launchIn(viewModelScope)
    }

    fun open(request: ReaderRequest) {
        val previousRequest = openedRequest
        val effectiveRequest = if (
            previousRequest?.bookId == request.bookId && request.privateEpubPath == null
        ) {
            request.copy(privateEpubPath = previousRequest.privateEpubPath)
        } else {
            request
        }

        val isSameActiveRequest = previousRequest == effectiveRequest
        val isSameBookWithoutRequestedLocator =
            previousRequest?.bookId == effectiveRequest.bookId && request.initialLocatorJson == null
        if (
            (isSameActiveRequest || isSameBookWithoutRequestedLocator) &&
            (loadState.value is ReaderLoadState.Ready || loadJob?.isActive == true)
        ) {
            // A MediaSession notification deliberately carries only the durable book ID. When it
            // is delivered to the existing Activity, keep the live Publication and narration
            // instead of treating the missing optional path as a different request.
            val retainedRequest = requireNotNull(previousRequest)
            openedRequest = retainedRequest.copy(
                privateEpubPath = retainedRequest.privateEpubPath
                    ?: effectiveRequest.privateEpubPath,
            )
            return
        }

        openedRequest = effectiveRequest
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableLoadState.value = ReaderLoadState.Loading
            releaseCurrentSession()
            mutableCurrentLocator.value = null
            latestProgressLocator = null
            mutableChapter.value = ChapterNavigationState()
            chapterCursor = null

            try {
                val storedPreferences = dependencies.preferencesStore.preferences.first()
                mutablePreferences.value = storedPreferences
                mutableEpubPreferences.value = storedPreferences.toEpubPreferences()

                val privatePath = effectiveRequest.privateEpubPath
                    ?: dependencies.bookRepository?.get(effectiveRequest.bookId)?.epubPath
                if (privatePath.isNullOrBlank()) {
                    mutableLoadState.value = ReaderLoadState.Error(
                        "找不到这本书的私有 EPUB 文件。",
                    )
                    return@launch
                }

                val requestedLocator = effectiveRequest.initialLocatorJson?.let { json ->
                    parseLocator(json, "所选书签已损坏，将恢复上次阅读位置。")
                }
                val durableLocator = if (requestedLocator == null) {
                    dependencies.readingProgressRepository
                        ?.get(effectiveRequest.bookId)
                        ?.locatorJson
                        ?.let { json ->
                            parseLocator(json, "上次阅读位置已损坏，将从书籍开头继续。")
                        }
                } else {
                    null
                }
                val initialLocator = requestedLocator ?: durableLocator

                when (val result = dependencies.publicationManager.openPrivateEpub(File(privatePath))) {
                    is PublicationManager.Outcome.Failure -> {
                        mutableLoadState.value = ReaderLoadState.Error(result.error.userMessage)
                    }

                    is PublicationManager.Outcome.Success -> {
                        val publication = result.value.publication
                        val chapters = publication.tableOfContents.flatten()
                            .ifEmpty { publication.readingOrder }
                        val session = ReaderSession(
                            request = effectiveRequest,
                            title = publication.metadata.title
                                ?.takeIf(String::isNotBlank)
                                ?: effectiveRequest.bookId,
                            publication = publication,
                            navigatorFactory = EpubNavigatorFactory(publication),
                            initialLocator = initialLocator,
                            initialPreferences = storedPreferences.toEpubPreferences(),
                            chapters = chapters,
                        )
                        currentSession = session
                        mutableLoadState.value = ReaderLoadState.Ready(session)
                        updateChapter(session, initialLocator)
                        try {
                            dependencies.bookRepository?.markRead(effectiveRequest.bookId)
                        } catch (error: Exception) {
                            mutableMessages.emit(
                                "最近阅读时间保存失败：${error.message.orEmpty()}",
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableLoadState.value = ReaderLoadState.Error(
                    "阅读器无法打开这本书：${error.message.orEmpty()}",
                )
            }
        }
    }

    fun attachNavigator(navigator: EpubNavigatorFragment) {
        if (visualNavigator === navigator) return

        visualNavigator?.let(::detachNavigator)
        visualNavigator = navigator
        navigator.submitPreferences(mutableEpubPreferences.value)
        acceptLocator(navigator.currentLocator.value)

        progressJob = navigator.currentLocator
            .onEach(::acceptLocator)
            .debounce(PROGRESS_SAVE_DEBOUNCE_MS)
            .onEach(::saveProgress)
            .catch { error ->
                mutableMessages.emit("阅读进度保存失败：${error.message.orEmpty()}")
            }
            .launchIn(viewModelScope)

        val narrationFactory = dependencies.narrationFactory
        val session = (loadState.value as? ReaderLoadState.Ready)?.session
        if (narrationFactory == null || session == null) {
            mutableNarration.value = TtsVisualSynchronizer.State(available = false)
            return
        }

        val narrationSession = dependencies.narrationSessionFactory(application)
        val newSynchronizer = TtsVisualSynchronizer(
            scope = readerScope,
            bookId = session.request.bookId,
            publication = session.publication,
            visualNavigator = navigator,
            narrationFactory = narrationFactory,
            narrationSession = narrationSession,
            automaticFollow = { mutablePreferences.value.automaticFollow },
            onNarrationLocator = { locator -> latestProgressLocator = locator },
        )
        synchronizer = newSynchronizer
        narrationStateJob = newSynchronizer.state
            .onEach { mutableNarration.value = it }
            .launchIn(readerScope)
    }

    fun detachNavigator(navigator: EpubNavigatorFragment) {
        if (visualNavigator !== navigator) return

        val bookIdToSave = currentSession?.request?.bookId
        val locatorToSave = latestProgressLocator
        visualNavigator = null
        progressJob?.cancel()
        progressJob = null
        narrationStateJob?.cancel()
        narrationStateJob = null

        val oldSynchronizer = synchronizer
        synchronizer = null
        mutableNarration.value = TtsVisualSynchronizer.State(
            available = dependencies.narrationFactory != null,
        )
        readerScope.launch {
            oldSynchronizer?.close()
            if (bookIdToSave != null && locatorToSave != null) {
                saveProgress(bookIdToSave, locatorToSave)
            }
        }
    }

    fun goToPreviousChapter() = goToChapter(-1)

    fun goToNextChapter() = goToChapter(1)

    fun goToChapterIndex(index: Int) {
        val session = (loadState.value as? ReaderLoadState.Ready)?.session ?: return
        navigateToChapter(session, index)
    }

    fun addBookmark() {
        val locator = mutableCurrentLocator.value ?: run {
            mutableMessages.tryEmit("当前位置尚未就绪。")
            return
        }
        val session = (loadState.value as? ReaderLoadState.Ready)?.session ?: return
        val repository = dependencies.bookmarkRepository ?: run {
            mutableMessages.tryEmit("书签存储尚未连接。")
            return
        }

        viewModelScope.launch {
            try {
                repository.save(
                    bookId = session.request.bookId,
                    locatorJson = locator.toJSON().toString(),
                    label = mutableChapter.value.title.takeIf(String::isNotBlank),
                )
                mutableMessages.emit("已添加书签。")
            } catch (error: Exception) {
                mutableMessages.emit("书签保存失败：${error.message.orEmpty()}")
            }
        }
    }

    fun cycleTheme() = updateReaderPreferences {
        copy(
            readerTheme = when (readerTheme) {
                ReaderTheme.DAY -> ReaderTheme.EYE_CARE
                ReaderTheme.EYE_CARE -> ReaderTheme.NIGHT
                ReaderTheme.NIGHT -> ReaderTheme.DAY
            },
        )
    }

    fun increaseFontSize() = updateReaderPreferences {
        copy(fontSizeSp = (fontSizeSp + 1f).coerceAtMost(48f))
    }

    fun decreaseFontSize() = updateReaderPreferences {
        copy(fontSizeSp = (fontSizeSp - 1f).coerceAtLeast(12f))
    }

    fun toggleNavigationMode() = updateReaderPreferences {
        copy(
            navigationMode = when (navigationMode) {
                ReaderNavigationMode.SCROLL -> ReaderNavigationMode.PAGINATED
                ReaderNavigationMode.PAGINATED -> ReaderNavigationMode.SCROLL
            },
        )
    }

    fun updateAdvancedReader(
        lineSpacing: Float,
        paragraphSpacing: Float,
        pageMarginDp: Float,
        textAlignment: ReaderTextAlignment,
        screenBrightness: Float,
        keepScreenOn: Boolean,
    ) {
        val current = mutablePreferences.value
        val updated = current.copy(
            lineSpacing = lineSpacing.coerceIn(1f, 3f),
            paragraphSpacing = paragraphSpacing.coerceIn(0f, 2f),
            pageMarginDp = pageMarginDp.coerceIn(0f, 64f),
            textAlignment = textAlignment,
            screenBrightness = if (screenBrightness < 0f) {
                -1f
            } else {
                screenBrightness.coerceIn(0.05f, 1f)
            },
            keepScreenOn = keepScreenOn,
        )

        // Publish before the DataStore write so Readium and the Activity window update live.
        mutablePreferences.value = updated
        mutableEpubPreferences.value = updated.toEpubPreferences()
        visualNavigator?.submitPreferences(mutableEpubPreferences.value)

        viewModelScope.launch {
            try {
                dependencies.preferencesStore.updateReader(
                    theme = updated.readerTheme,
                    fontSizeSp = updated.fontSizeSp,
                    lineSpacing = updated.lineSpacing,
                    pageMarginDp = updated.pageMarginDp,
                    navigationMode = updated.navigationMode,
                )
                dependencies.preferencesStore.updateAdvancedReader(
                    paragraphSpacing = updated.paragraphSpacing,
                    textAlignment = updated.textAlignment,
                    screenBrightness = updated.screenBrightness,
                    keepScreenOn = updated.keepScreenOn,
                )
            } catch (error: Exception) {
                mutableMessages.emit("阅读设置保存失败：${error.message.orEmpty()}")
            }
        }
    }

    fun updateNarration(
        voiceSid: Int,
        speed: Float,
        style: NarrationStyle,
        automaticStyle: Boolean,
        automaticFollow: Boolean,
    ) {
        val current = mutablePreferences.value
        val updated = current.copy(
            defaultVoiceSid = voiceSid.coerceIn(3, 102),
            narrationSpeed = speed.coerceIn(0.5f, 2f),
            narrationStyle = style,
            automaticStyle = automaticStyle,
            automaticFollow = automaticFollow,
        )
        val engineSettingsChanged =
            current.defaultVoiceSid != updated.defaultVoiceSid ||
                current.narrationSpeed != updated.narrationSpeed ||
                current.narrationStyle != updated.narrationStyle ||
                current.automaticStyle != updated.automaticStyle

        // Automatic follow reads this state dynamically and therefore changes immediately.
        mutablePreferences.value = updated
        viewModelScope.launch {
            try {
                dependencies.preferencesStore.updateNarration(
                    voiceSid = updated.defaultVoiceSid,
                    speed = updated.narrationSpeed,
                    style = updated.narrationStyle,
                    automaticStyle = updated.automaticStyle,
                    automaticFollow = updated.automaticFollow,
                )
                // The type-erased navigator intentionally hides concrete engine preferences.
                // Recreate it on the next play so voice/speed/style changes take effect safely.
                if (engineSettingsChanged) synchronizer?.stop()
            } catch (error: Exception) {
                mutableMessages.emit("朗读设置保存失败：${error.message.orEmpty()}")
            }
        }
    }

    fun playNarration() = synchronizer?.play()
        ?: mutableMessages.tryEmit("朗读引擎尚未就绪。")

    fun pauseNarration() = synchronizer?.pause()

    fun previousUtterance() = synchronizer?.previous()

    fun nextUtterance() = synchronizer?.next()

    fun stopNarration() = synchronizer?.stop()

    fun closeReader() {
        loadJob?.cancel()
        viewModelScope.launch { releaseCurrentSession() }
    }

    private fun goToChapter(delta: Int) {
        val session = (loadState.value as? ReaderLoadState.Ready)?.session ?: return
        val navigator = visualNavigator ?: return
        val chapters = session.chapters
        if (chapters.isEmpty()) {
            mutableMessages.tryEmit("这本书没有可用的章节目录。")
            return
        }

        val currentIndex = chapterIndex(session, mutableCurrentLocator.value)
        val targetIndex = when {
            currentIndex < 0 && delta > 0 -> 0
            currentIndex < 0 -> -1
            else -> currentIndex + delta
        }
        if (chapters.getOrNull(targetIndex) == null) {
            mutableMessages.tryEmit(if (delta < 0) "已经是第一章。" else "已经是最后一章。")
            return
        }
        navigateToChapter(session, targetIndex)
    }

    private fun navigateToChapter(session: ReaderSession, targetIndex: Int) {
        val navigator = visualNavigator ?: return
        val chapters = session.chapters
        val target = chapters.getOrNull(targetIndex)
        if (target == null) {
            mutableMessages.tryEmit("所选章节不存在。")
            return
        }

        if (!navigator.go(target, animated = false)) {
            mutableMessages.tryEmit("无法跳转到所选章节。")
        } else {
            chapterCursor = targetIndex
            mutableChapter.value = ChapterNavigationState(
                title = target.title ?: session.title,
                canGoPrevious = targetIndex > 0,
                canGoNext = targetIndex < chapters.lastIndex,
            )
        }
    }

    private fun updateReaderPreferences(
        transform: ReaderTtsPreferences.() -> ReaderTtsPreferences,
    ) {
        val updated = mutablePreferences.value.transform()
        mutablePreferences.value = updated
        mutableEpubPreferences.value = updated.toEpubPreferences()
        visualNavigator?.submitPreferences(mutableEpubPreferences.value)
        viewModelScope.launch {
            try {
                dependencies.preferencesStore.updateReader(
                    theme = updated.readerTheme,
                    fontSizeSp = updated.fontSizeSp,
                    lineSpacing = updated.lineSpacing,
                    pageMarginDp = updated.pageMarginDp,
                    navigationMode = updated.navigationMode,
                )
            } catch (error: Exception) {
                mutableMessages.emit("阅读设置保存失败：${error.message.orEmpty()}")
            }
        }
    }

    private fun acceptLocator(locator: Locator) {
        mutableCurrentLocator.value = locator
        latestProgressLocator = locator
        (loadState.value as? ReaderLoadState.Ready)?.session?.let { session ->
            updateChapter(session, locator)
        }
    }

    private fun updateChapter(session: ReaderSession, locator: Locator?) {
        val index = chapterIndex(session, locator)
        mutableChapter.value = ChapterNavigationState(
            title = session.chapters.getOrNull(index)?.title
                ?: locator?.title
                ?: session.title,
            canGoPrevious = index > 0,
            canGoNext = index >= 0 && index < session.chapters.lastIndex,
        )
    }

    private fun chapterIndex(session: ReaderSession, locator: Locator?): Int {
        locator ?: return -1
        val currentUrl = session.publication.url(locator).removeFragment()
        val matchingIndices = session.chapters.indices.filter { index ->
            session.publication.url(session.chapters[index])
                .removeFragment()
                .isEquivalent(currentUrl)
        }
        val retainedCursor = chapterCursor?.takeIf(matchingIndices::contains)
        return (retainedCursor ?: matchingIndices.firstOrNull() ?: -1)
            .also { chapterCursor = it.takeIf { index -> index >= 0 } }
    }

    private suspend fun saveProgress(locator: Locator) {
        val session = currentSession ?: return
        saveProgress(session.request.bookId, locator)
    }

    private suspend fun saveProgress(bookId: String, locator: Locator) {
        dependencies.readingProgressRepository?.save(
            bookId = bookId,
            locatorJson = locator.toJSON().toString(),
            totalProgression = locator.locations.totalProgression,
        )
    }

    private suspend fun releaseCurrentSession() {
        val sessionToClose = currentSession
        currentSession = null
        progressJob?.cancel()
        progressJob = null
        narrationStateJob?.cancel()
        narrationStateJob = null
        visualNavigator = null

        val oldSynchronizer = synchronizer
        synchronizer = null
        try {
            oldSynchronizer?.close()
        } finally {
            val lastLocator = latestProgressLocator
            if (sessionToClose != null && lastLocator != null) {
                try {
                    saveProgress(sessionToClose.request.bookId, lastLocator)
                } catch (_: Exception) {
                    // Closing the publication must not be prevented by a database failure.
                }
            }
            sessionToClose?.publication?.close()
            mutableNarration.value = TtsVisualSynchronizer.State(
                available = dependencies.narrationFactory != null,
            )
        }
    }

    private fun parseLocator(json: String, warning: String): Locator? = try {
        Locator.fromJSON(JSONObject(json)) ?: run {
            mutableMessages.tryEmit(warning)
            null
        }
    } catch (_: Exception) {
        mutableMessages.tryEmit(warning)
        null
    }

    override fun onCleared() {
        val sessionToClose = currentSession
        currentSession = null
        val oldSynchronizer = synchronizer
        synchronizer = null
        val lastLocator = latestProgressLocator
        readerScope.launch {
            try {
                oldSynchronizer?.close()
            } finally {
                if (sessionToClose != null && lastLocator != null) {
                    try {
                        saveProgress(sessionToClose.request.bookId, lastLocator)
                    } catch (_: Exception) {
                        // Best-effort flush during ViewModel teardown.
                    }
                }
                sessionToClose?.publication?.close()
                readerScope.cancel()
            }
        }
        super.onCleared()
    }

    class Factory(
        private val application: Application,
        private val dependencies: ReaderDependencies,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ReaderViewModel::class.java))
            return ReaderViewModel(application, dependencies) as T
        }
    }

    private companion object {
        const val PROGRESS_SAVE_DEBOUNCE_MS = 750L
    }
}

private fun ReaderTtsPreferences.toEpubPreferences(): EpubPreferences = EpubPreferences(
    theme = when (readerTheme) {
        ReaderTheme.DAY -> Theme.LIGHT
        ReaderTheme.NIGHT -> Theme.DARK
        ReaderTheme.EYE_CARE -> Theme.SEPIA
    },
    fontSize = fontSizeSp.toDouble() / DEFAULT_FONT_SIZE_SP,
    lineHeight = lineSpacing.toDouble(),
    pageMargins = pageMarginDp.toDouble() / DEFAULT_PAGE_MARGIN_DP,
    paragraphSpacing = paragraphSpacing.toDouble(),
    textAlign = when (textAlignment) {
        ReaderTextAlignment.PUBLISHER -> null
        ReaderTextAlignment.START -> TextAlign.START
        ReaderTextAlignment.JUSTIFY -> TextAlign.JUSTIFY
    },
    // Readium CSS only applies these author-style-sensitive controls when publisher styles are
    // disabled. Leave them untouched when the user explicitly selected publisher alignment and
    // no added paragraph spacing.
    publisherStyles = if (
        paragraphSpacing > 0f || textAlignment != ReaderTextAlignment.PUBLISHER
    ) {
        false
    } else {
        null
    },
    scroll = navigationMode == ReaderNavigationMode.SCROLL,
)

private const val DEFAULT_FONT_SIZE_SP = 18.0
private const val DEFAULT_PAGE_MARGIN_DP = 20.0

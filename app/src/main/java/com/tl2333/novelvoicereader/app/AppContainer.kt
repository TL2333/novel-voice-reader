package com.tl2333.novelvoicereader.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.tl2333.novelvoicereader.data.database.BookEntity
import com.tl2333.novelvoicereader.data.database.BookRepository
import com.tl2333.novelvoicereader.data.database.BookmarkRepository
import com.tl2333.novelvoicereader.data.database.NarrationCacheRepository
import com.tl2333.novelvoicereader.data.database.NovelVoiceDatabase
import com.tl2333.novelvoicereader.data.database.ReadingProgressRepository
import com.tl2333.novelvoicereader.data.preferences.ReaderTtsPreferencesStore
import com.tl2333.novelvoicereader.playback.NarrationMediaServiceClient
import com.tl2333.novelvoicereader.reader.PublicationManager
import com.tl2333.novelvoicereader.reader.ReaderDependencies
import com.tl2333.novelvoicereader.reader.ReaderNarrationFactory
import com.tl2333.novelvoicereader.tts.cache.CacheClearResult
import com.tl2333.novelvoicereader.tts.cache.TtsAudioCache
import com.tl2333.novelvoicereader.ui.diagnostics.DiagnosticsController
import com.tl2333.novelvoicereader.ui.diagnostics.UnavailableDiagnosticsController
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class KokoroIntegration(
    val narrationFactory: ReaderNarrationFactory,
    val diagnosticsController: DiagnosticsController,
)

data class LibraryActionResult(
    val successful: Boolean,
    val message: String,
    val bookId: String? = null,
)

data class StorageSnapshot(
    val booksBytes: Long,
    val narrationCacheBytes: Long,
    val availableBytes: Long,
)

class AppContainer(
    val application: Application,
    kokoroIntegration: KokoroIntegration? = null,
    preferencesStore: ReaderTtsPreferencesStore = ReaderTtsPreferencesStore(application),
) {
    val database: NovelVoiceDatabase = NovelVoiceDatabase.open(application)
    val books = BookRepository(database.books())
    val readingProgress = ReadingProgressRepository(database.readingProgress())
    val bookmarks = BookmarkRepository(database.bookmarks())
    val narrationCache = NarrationCacheRepository(database.narrationCache())
    val preferences = preferencesStore
    val publicationManager = PublicationManager(application)

    val diagnosticsController: DiagnosticsController = kokoroIntegration?.diagnosticsController
        ?: UnavailableDiagnosticsController()

    val readerDependencies = ReaderDependencies(
        publicationManager = publicationManager,
        preferencesStore = preferences,
        bookRepository = books,
        readingProgressRepository = readingProgress,
        bookmarkRepository = bookmarks,
        narrationFactory = kokoroIntegration?.narrationFactory,
        narrationSessionFactory = { NarrationMediaServiceClient(it, readingProgress) },
    )

    suspend fun importSafDocument(uri: Uri): LibraryActionResult {
        runCatching {
            application.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        return importUri(uri)
    }

    suspend fun importBuiltInTestBook(): LibraryActionResult = withContext(Dispatchers.IO) {
        val stagingDirectory = File(application.cacheDir, "builtin-import").apply { mkdirs() }
        val stagedBook = File(stagingDirectory, BUILT_IN_BOOK_NAME)
        try {
            application.assets.open(BUILT_IN_BOOK_ASSET).use { input ->
                stagedBook.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            importUri(Uri.fromFile(stagedBook))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LibraryActionResult(false, "内置测试书导入失败：${error.message.orEmpty()}")
        } finally {
            stagedBook.delete()
        }
    }

    suspend fun deleteBook(bookId: String): LibraryActionResult = withContext(Dispatchers.IO) {
        val book = books.get(bookId)
            ?: return@withContext LibraryActionResult(false, "找不到要删除的书籍。")
        if (!clearBookCacheInternal(bookId).successful) {
            return@withContext LibraryActionResult(false, "无法完全删除本书朗读缓存，书籍未删除。")
        }
        val cover = book.coverPath?.let(::File)
        if (cover != null && !publicationManager.deleteCover(cover)) {
            return@withContext LibraryActionResult(false, "无法删除本书封面文件，书籍未删除。")
        }
        val epub = File(book.epubPath)
        if (publicationManager.isPrivateBook(epub) && epub.exists() && !epub.delete()) {
            return@withContext LibraryActionResult(false, "无法删除私有 EPUB 文件。")
        }
        books.delete(bookId)
        LibraryActionResult(true, "已删除《${book.title}》。")
    }

    suspend fun clearBookCache(bookId: String): LibraryActionResult = withContext(Dispatchers.IO) {
        val result = clearBookCacheInternal(bookId)
        when {
            !result.successful -> LibraryActionResult(false, "本书朗读缓存未能完全删除。")
            result.retainedProtectedFiles.isNotEmpty() -> LibraryActionResult(
                true,
                "已清除本书朗读缓存；当前播放文件将在播放结束后删除。",
            )

            else -> LibraryActionResult(true, "已清除本书朗读缓存。")
        }
    }

    suspend fun clearAllNarrationCache(): LibraryActionResult = withContext(Dispatchers.IO) {
        val directory = narrationCacheDirectory()
        val result = TtsAudioCache.clearAll(directory)
        if (!result.successful) {
            return@withContext LibraryActionResult(false, "朗读缓存文件未能完全删除。")
        }
        narrationCache.clearAll()
        LibraryActionResult(
            true,
            if (result.retainedProtectedFiles.isEmpty()) {
                "已清除全部朗读缓存，书籍、书签和进度未受影响。"
            } else {
                "已清除全部朗读缓存；当前播放文件将在播放结束后删除，书籍、书签和进度未受影响。"
            },
        )
    }

    suspend fun storageSnapshot(): StorageSnapshot = withContext(Dispatchers.IO) {
        StorageSnapshot(
            booksBytes = directorySize(publicationManager.booksDirectory),
            narrationCacheBytes = directorySize(narrationCacheDirectory()),
            availableBytes = application.filesDir.usableSpace,
        )
    }

    private suspend fun importUri(uri: Uri): LibraryActionResult = when (
        val result = publicationManager.importFromSaf(uri)
    ) {
        is PublicationManager.Outcome.Failure -> LibraryActionResult(false, result.error.userMessage)
        is PublicationManager.Outcome.Success -> {
            val imported = result.value
            val existing = books.getBySha256(imported.sha256)
            val existingCoverPath = existing?.coverPath?.takeIf { path ->
                File(path).let { it.isFile && it.length() > 0L }
            }
            if (
                existingCoverPath != null &&
                imported.coverPath != null &&
                existingCoverPath != imported.coverPath
            ) {
                publicationManager.deleteCover(File(imported.coverPath))
            }
            val now = System.currentTimeMillis()
            val entity = BookEntity(
                id = existing?.id ?: imported.sha256,
                title = imported.title,
                author = imported.author,
                coverPath = existingCoverPath ?: imported.coverPath,
                epubPath = imported.file.absolutePath,
                epubSha256 = imported.sha256,
                mediaType = imported.mediaType,
                createdAt = existing?.createdAt ?: now,
                lastReadAt = existing?.lastReadAt,
            )
            try {
                books.save(entity)
                LibraryActionResult(
                    successful = true,
                    message = if (existing == null) "已导入《${entity.title}》。" else "《${entity.title}》已在书架中。",
                    bookId = entity.id,
                )
            } catch (error: CancellationException) {
                if (existing == null) cleanupFailedImport(imported)
                throw error
            } catch (error: Exception) {
                if (existing == null) cleanupFailedImport(imported)
                LibraryActionResult(false, "EPUB 已验证，但无法保存到书架：${error.message.orEmpty()}")
            }
        }
    }

    private fun cleanupFailedImport(imported: PublicationManager.ImportedEpub) {
        if (publicationManager.isPrivateBook(imported.file)) imported.file.delete()
        imported.coverPath?.let { publicationManager.deleteCover(File(it)) }
    }

    private suspend fun clearBookCacheInternal(bookId: String): CacheClearResult {
        val result = TtsAudioCache.clearBook(narrationCacheDirectory(), bookId)
        if (result.successful) narrationCache.clearBook(bookId)
        return result
    }

    private fun narrationCacheDirectory(): File = File(application.cacheDir, "narration")

    private fun directorySize(directory: File): Long =
        if (!directory.isDirectory) 0L else directory.walkTopDown()
            .filter(File::isFile)
            .fold(0L) { total, file -> runCatching { Math.addExact(total, file.length()) }.getOrDefault(Long.MAX_VALUE) }

    companion object {
        const val BUILT_IN_BOOK_ASSET = "testbooks/storyvoice-test.epub"
        const val BUILT_IN_BOOK_NAME = "storyvoice-test.epub"
    }
}

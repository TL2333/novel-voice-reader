package com.tl2333.novelvoicereader.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.tl2333.novelvoicereader.content.docx.DocxImporter
import com.tl2333.novelvoicereader.content.docx.LegacyDocImporter
import com.tl2333.novelvoicereader.content.docx.LegacyDocUnsupportedException
import com.tl2333.novelvoicereader.content.model.CanonicalDocument
import com.tl2333.novelvoicereader.content.model.CanonicalDocumentCodec
import com.tl2333.novelvoicereader.content.model.SourceType
import com.tl2333.novelvoicereader.content.pdf.PdfImporter
import com.tl2333.novelvoicereader.content.txt.TxtImporter
import com.tl2333.novelvoicereader.content.web.WebImporter
import com.tl2333.novelvoicereader.content.web.WebContentKind
import com.tl2333.novelvoicereader.content.web.WebImportResult
import com.tl2333.novelvoicereader.content.web.WebSnapshotRepository
import com.tl2333.novelvoicereader.data.database.BookEntity
import com.tl2333.novelvoicereader.data.database.BookRepository
import com.tl2333.novelvoicereader.data.database.BookmarkRepository
import com.tl2333.novelvoicereader.data.database.NarrationCacheRepository
import com.tl2333.novelvoicereader.data.database.NovelVoiceDatabase
import com.tl2333.novelvoicereader.data.database.ReadingProgressRepository
import com.tl2333.novelvoicereader.data.preferences.ReaderTtsPreferencesStore
import com.tl2333.novelvoicereader.playback.NarrationMediaServiceClient
import com.tl2333.novelvoicereader.narration.DeviceTtsBenchmark
import com.tl2333.novelvoicereader.reader.PublicationManager
import com.tl2333.novelvoicereader.reader.ReaderDependencies
import com.tl2333.novelvoicereader.reader.ReaderNarrationFactory
import com.tl2333.novelvoicereader.tts.cache.CacheClearResult
import com.tl2333.novelvoicereader.tts.cache.AudioCacheCapacityPolicy
import com.tl2333.novelvoicereader.tts.cache.TtsAudioCache
import com.tl2333.novelvoicereader.ui.diagnostics.DiagnosticsController
import com.tl2333.novelvoicereader.ui.diagnostics.UnavailableDiagnosticsController
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    val narrationCacheLimitBytes: Long,
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
        val source = sourceInfo(uri)
        return when (source.type) {
            SourceType.EPUB -> importUri(uri)
            SourceType.TXT, SourceType.PDF, SourceType.DOCX, SourceType.DOC -> importCanonicalUri(uri, source)
            else -> LibraryActionResult(false, "暂不支持该文件格式：${source.displayName}")
        }
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

    suspend fun importWebUrl(url: String): LibraryActionResult = withContext(Dispatchers.IO) {
        try {
            when (val result = WebImporter(application).import(url)) {
                is WebImportResult.Article -> saveWebArticle(result)
                is WebImportResult.DirectDocument -> saveWebDirectDocument(result)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LibraryActionResult(false, "网页导入失败：${error.message.orEmpty()}")
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
        val source = File(book.epubPath)
        if (isPrivateDocument(source) && source.exists() && !source.delete()) {
            return@withContext LibraryActionResult(false, "无法删除应用私有文档文件。")
        }
        book.canonicalDocumentPath?.let(::File)?.takeIf(::isPrivateDocument)?.delete()
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
            narrationCacheLimitBytes = AudioCacheCapacityPolicy.capacityFor(application.cacheDir.usableSpace),
            availableBytes = application.filesDir.usableSpace,
        )
    }

    suspend fun exportNarrationTraces(destination: Uri): LibraryActionResult = withContext(Dispatchers.IO) {
        val traces = File(application.filesDir, "narration-traces").listFiles()
            ?.filter { it.isFile && it.extension == "jsonl" }
            ?.sortedBy(File::lastModified)
            .orEmpty()
        if (traces.isEmpty()) return@withContext LibraryActionResult(false, "尚无可导出的朗读诊断日志。")
        try {
            application.contentResolver.openOutputStream(destination, "w")?.buffered()?.use { output ->
                traces.forEach { trace -> trace.inputStream().buffered().use { it.copyTo(output, 64 * 1024) } }
            } ?: return@withContext LibraryActionResult(false, "无法打开导出位置。")
            LibraryActionResult(true, "已导出 ${traces.size} 个朗读会话日志。")
        } catch (error: Exception) {
            LibraryActionResult(false, "朗读日志导出失败：${error.message.orEmpty()}")
        }
    }

    suspend fun runDeviceTtsBenchmark(): LibraryActionResult = withContext(Dispatchers.IO) {
        try {
            val voiceSid = preferences.preferences.first().defaultVoiceSid
            val result = DeviceTtsBenchmark.run(application, voiceSid)
            LibraryActionResult(
                true,
                "性能测试完成：p50=${"%.3f".format(result.statistics.p50)}，p95=${"%.3f".format(result.statistics.p95)}。",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LibraryActionResult(false, "朗读性能测试失败：${error.message.orEmpty()}")
        }
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

    private suspend fun importCanonicalUri(uri: Uri, source: SourceInfo): LibraryActionResult = withContext(Dispatchers.IO) {
        val stagingDirectory = File(application.cacheDir, "document-import").apply { mkdirs() }
        val staged = File(stagingDirectory, ".${UUID.randomUUID()}.${source.extension}")
        try {
            application.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().buffered(64 * 1024).use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return@withContext LibraryActionResult(false, "无法读取所选文档。")
            if (staged.length() <= 0L) return@withContext LibraryActionResult(false, "所选文档为空。")
            val canonical = when (source.type) {
                SourceType.TXT -> TxtImporter().import(staged, uri.toString(), source.displayName.substringBeforeLast('.'))
                SourceType.DOCX -> DocxImporter().import(staged, uri.toString(), source.displayName.substringBeforeLast('.'))
                SourceType.DOC -> LegacyDocImporter().import(staged)
                SourceType.PDF -> PdfImporter().import(staged, uri.toString(), source.displayName.substringBeforeLast('.'))
                else -> error("Unsupported canonical source ${source.type}")
            }
            saveCanonicalImport(staged, source, canonical)
        } catch (error: LegacyDocUnsupportedException) {
            LibraryActionResult(false, error.message.orEmpty())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LibraryActionResult(false, "${source.type.name} 导入失败：${error.message.orEmpty()}")
        } finally {
            staged.delete()
        }
    }

    private suspend fun saveCanonicalImport(
        staged: File,
        source: SourceInfo,
        canonical: CanonicalDocument,
    ): LibraryActionResult {
        val existing = books.getBySha256(canonical.contentHash)
        if (existing != null) return LibraryActionResult(true, "《${existing.title}》已在书架中。", existing.id)
        val documentDirectory = File(application.filesDir, "documents").apply { mkdirs() }
        val canonicalDirectory = File(application.filesDir, "canonical").apply { mkdirs() }
        val destination = File(documentDirectory, "${canonical.contentHash}.${source.extension}")
        val canonicalFile = File(canonicalDirectory, "${canonical.contentHash}.json")
        moveAtomically(staged, destination)
        try {
            CanonicalDocumentCodec.writeAtomically(canonical, canonicalFile)
            books.save(
                BookEntity(
                    id = canonical.id,
                    title = canonical.title,
                    author = canonical.author,
                    coverPath = null,
                    epubPath = destination.absolutePath,
                    epubSha256 = canonical.contentHash,
                    mediaType = source.mediaType,
                    createdAt = System.currentTimeMillis(),
                    lastReadAt = null,
                    sourceType = source.type.name,
                    canonicalDocumentPath = canonicalFile.absolutePath,
                    sourceDetail = canonical.sourceDetail(),
                ),
            )
            return LibraryActionResult(true, "已导入《${canonical.title}》。", canonical.id)
        } catch (error: Exception) {
            destination.delete()
            canonicalFile.delete()
            throw error
        }
    }

    private suspend fun saveWebArticle(result: WebImportResult.Article): LibraryActionResult {
        val snapshot = result.snapshot
        books.save(
            BookEntity(
                id = snapshot.id,
                title = snapshot.title,
                author = snapshot.author,
                coverPath = null,
                epubPath = snapshot.htmlPath,
                epubSha256 = snapshot.contentHash,
                mediaType = "text/html",
                createdAt = snapshot.fetchedAt,
                lastReadAt = null,
                sourceType = SourceType.WEB.name,
                canonicalDocumentPath = snapshot.canonicalDocumentPath,
                sourceDetail = runCatching { java.net.URI(snapshot.canonicalUrl).host }.getOrNull(),
            ),
        )
        return LibraryActionResult(
            true,
            if (result.usedDynamicFallback) "已通过动态页面快照导入《${snapshot.title}》。" else "已导入网页《${snapshot.title}》。",
            snapshot.id,
        )
    }

    private suspend fun saveWebDirectDocument(result: WebImportResult.DirectDocument): LibraryActionResult {
        val snapshotRepository = WebSnapshotRepository(File(application.filesDir, "web-snapshots"))
        val hash = com.tl2333.novelvoicereader.filesystem.Sha256.hash(result.bytes)
        if (result.kind == WebContentKind.EPUB) {
            val snapshotFile = snapshotRepository.createRawDownload(
                hash,
                result.requestedUrl,
                result.finalUrl,
                result.fetchedAt,
                result.kind,
                result.bytes,
            )
            return importUri(Uri.fromFile(snapshotFile))
        }
        val remoteName = runCatching { java.net.URI(result.finalUrl).path.substringAfterLast('/').takeIf(String::isNotBlank) }.getOrNull()
        val source = when (result.kind) {
            WebContentKind.TXT -> SourceInfo(remoteName ?: "download.txt", "txt", "text/plain", SourceType.TXT)
            WebContentKind.DOCX -> SourceInfo(remoteName ?: "download.docx", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", SourceType.DOCX)
            WebContentKind.PDF -> SourceInfo(remoteName ?: "download.pdf", "pdf", "application/pdf", SourceType.PDF)
            else -> return LibraryActionResult(false, "该直链格式无法交给文档导入器。")
        }
        val staging = File(application.cacheDir, "web-document-import/.${UUID.randomUUID()}.${source.extension}")
        staging.parentFile?.mkdirs()
        try {
            staging.writeBytes(result.bytes)
            val canonical = when (source.type) {
                SourceType.TXT -> TxtImporter().import(staging, result.finalUrl, source.displayName.substringBeforeLast('.'))
                SourceType.DOCX -> DocxImporter().import(staging, result.finalUrl, source.displayName.substringBeforeLast('.'))
                SourceType.PDF -> PdfImporter().import(staging, result.finalUrl, source.displayName.substringBeforeLast('.'))
                else -> error("Unsupported direct import")
            }
            books.getBySha256(canonical.contentHash)?.let { existing ->
                return LibraryActionResult(true, "《${existing.title}》已在书架中。", existing.id)
            }
            val snapshot = snapshotRepository.createDirect(
                result.requestedUrl,
                result.finalUrl,
                result.fetchedAt,
                result.kind,
                result.bytes,
                canonical,
            )
            books.save(
                BookEntity(
                    id = canonical.id,
                    title = canonical.title,
                    author = canonical.author,
                    coverPath = null,
                    epubPath = snapshot.htmlPath,
                    epubSha256 = canonical.contentHash,
                    mediaType = source.mediaType,
                    createdAt = result.fetchedAt,
                    lastReadAt = null,
                    sourceType = source.type.name,
                    canonicalDocumentPath = snapshot.canonicalDocumentPath,
                    sourceDetail = canonical.sourceDetail(),
                ),
            )
            return LibraryActionResult(true, "已从直链导入《${canonical.title}》。", canonical.id)
        } finally {
            staging.delete()
        }
    }

    private fun sourceInfo(uri: Uri): SourceInfo {
        var displayName: String? = null
        application.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) displayName = cursor.getString(0)
        }
        val name = displayName ?: uri.lastPathSegment ?: "document"
        val extension = name.substringAfterLast('.', "").lowercase()
        val mediaType = application.contentResolver.getType(uri).orEmpty()
        val type = when {
            extension == "epub" || mediaType == "application/epub+zip" -> SourceType.EPUB
            extension == "txt" || mediaType.startsWith("text/plain") -> SourceType.TXT
            extension == "docx" || mediaType.contains("wordprocessingml") -> SourceType.DOCX
            extension == "doc" || mediaType == "application/msword" -> SourceType.DOC
            extension == "pdf" || mediaType == "application/pdf" -> SourceType.PDF
            else -> SourceType.WEB
        }
        return SourceInfo(name, extension.ifBlank { type.name.lowercase() }, mediaType.ifBlank { "application/octet-stream" }, type)
    }

    private fun moveAtomically(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isPrivateDocument(file: File): Boolean {
        val root = application.filesDir.canonicalFile.toPath()
        return runCatching { file.canonicalFile.toPath().startsWith(root) }.getOrDefault(false)
    }

    private data class SourceInfo(
        val displayName: String,
        val extension: String,
        val mediaType: String,
        val type: SourceType,
    )

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

    private fun CanonicalDocument.sourceDetail(): String? = when (sourceType) {
        SourceType.TXT -> metadata["characterCount"]?.let { "$it 字" }
        SourceType.PDF -> metadata["pageCount"]?.let { "$it 页" }
        SourceType.DOCX -> metadata["paragraphCount"]?.let { "$it 段" }
        SourceType.WEB -> runCatching { java.net.URI(sourceUri).host }.getOrNull()
        else -> null
    }

    private fun directorySize(directory: File): Long =
        if (!directory.isDirectory) 0L else directory.walkTopDown()
            .filter(File::isFile)
            .fold(0L) { total, file -> runCatching { Math.addExact(total, file.length()) }.getOrDefault(Long.MAX_VALUE) }

    companion object {
        const val BUILT_IN_BOOK_ASSET = "testbooks/storyvoice-test.epub"
        const val BUILT_IN_BOOK_NAME = "storyvoice-test.epub"
    }
}

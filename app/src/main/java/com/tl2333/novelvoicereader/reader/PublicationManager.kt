@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.tl2333.novelvoicereader.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Size
import com.tl2333.novelvoicereader.filesystem.EpubImportLimits
import com.tl2333.novelvoicereader.filesystem.EpubImportPolicy
import com.tl2333.novelvoicereader.filesystem.EpubImportRejection
import com.tl2333.novelvoicereader.filesystem.EpubSignature
import com.tl2333.novelvoicereader.filesystem.Sha256
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.publication.services.coverFitting
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.publication.services.protectionError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/** Imports and opens EPUB files without granting the reader access outside its private book store. */
class PublicationManager(
    context: Context,
    httpClient: HttpClient = OfflineHttpClient(),
    private val importLimits: EpubImportLimits = EpubImportLimits(),
) {
    private val applicationContext = context.applicationContext

    val booksDirectory: File =
        File(applicationContext.filesDir, BOOKS_DIRECTORY_NAME).apply { mkdirs() }

    val coversDirectory: File =
        File(applicationContext.filesDir, COVERS_DIRECTORY_NAME).apply { mkdirs() }

    private val assetRetriever =
        AssetRetriever(applicationContext.contentResolver, httpClient)

    private val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context = applicationContext,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = null,
        ),
    )

    data class ImportedEpub(
        val file: File,
        val sha256: String,
        val byteCount: Long,
        val title: String,
        val author: String?,
        val mediaType: String,
        val coverPath: String?,
    )

    data class OpenedEpub(
        val file: File,
        val publication: Publication,
    )

    sealed interface Outcome<out T> {
        data class Success<T>(val value: T) : Outcome<T>
        data class Failure(val error: PublicationError) : Outcome<Nothing>
    }

    sealed class PublicationError(
        open val userMessage: String,
        open val cause: Throwable? = null,
    ) {
        data class SourceUnavailable(
            override val userMessage: String,
            override val cause: Throwable? = null,
        ) : PublicationError(userMessage, cause)

        data class CorruptedEpub(
            override val userMessage: String,
            override val cause: Throwable? = null,
        ) : PublicationError(userMessage, cause)

        data class UnsupportedFormat(
            override val userMessage: String = "所选文件不是可支持的 EPUB。",
        ) : PublicationError(userMessage)

        data class RestrictedPublication(
            override val userMessage: String,
        ) : PublicationError(userMessage)

        data class NoReadableText(
            override val userMessage: String = "此 EPUB 没有可朗读文本。",
        ) : PublicationError(userMessage)

        data class EmptyDocument(
            override val userMessage: String = "所选 EPUB 是空文件。",
        ) : PublicationError(userMessage)

        data class ImportTooLarge(
            override val userMessage: String,
        ) : PublicationError(userMessage)

        data class InsufficientStorage(
            override val userMessage: String,
        ) : PublicationError(userMessage)

        data class PrivateStorageFailure(
            override val userMessage: String,
            override val cause: Throwable? = null,
        ) : PublicationError(userMessage, cause)
    }

    /**
     * Copies a SAF document into private storage, validates it with both a ZIP signature check and
     * Readium, then atomically publishes it under its SHA-256 name.
     */
    suspend fun importFromSaf(uri: Uri): Outcome<ImportedEpub> = withContext(Dispatchers.IO) {
        val temporaryFile = File(booksDirectory, ".import-${UUID.randomUUID()}.part")
        val temporaryCover = File(coversDirectory, ".cover-${UUID.randomUUID()}.part")
        try {
            copyUri(uri, temporaryFile)

            val signature = EpubSignature.verify(temporaryFile)
            if (!signature.isValid) {
                return@withContext Outcome.Failure(
                    PublicationError.CorruptedEpub(
                        signature.message ?: "所选文件的 EPUB 结构无效。",
                    ),
                )
            }

            val digest = Sha256.digest(temporaryFile)
            val metadata = when (val result = inspectEpub(temporaryFile, temporaryCover)) {
                is Outcome.Success -> result.value
                is Outcome.Failure -> return@withContext result
            }

            val destination = File(booksDirectory, "${digest.hexDigest}.epub")
            if (destination.exists()) {
                if (destination.length() != digest.byteCount) {
                    return@withContext Outcome.Failure(
                        PublicationError.PrivateStorageFailure(
                            "私有书库中存在校验值相同但大小冲突的 EPUB。",
                        ),
                    )
                }
                temporaryFile.delete()
            } else {
                moveAtomically(temporaryFile, destination)
            }

            val coverDestination = File(coversDirectory, "${digest.hexDigest}.jpg")
            if (coverDestination.exists() && coverDestination.length() <= 0L) {
                coverDestination.delete()
            }
            if (temporaryCover.isFile && temporaryCover.length() > 0L) {
                if (coverDestination.exists()) {
                    temporaryCover.delete()
                } else {
                    try {
                        moveAtomically(temporaryCover, coverDestination)
                    } catch (_: IOException) {
                        // A missing cover is supported and must not roll back a valid EPUB import.
                        temporaryCover.delete()
                    } catch (_: SecurityException) {
                        temporaryCover.delete()
                    }
                }
            }

            Outcome.Success(
                ImportedEpub(
                    file = destination,
                    sha256 = digest.hexDigest,
                    byteCount = digest.byteCount,
                    title = metadata.title,
                    author = metadata.author,
                    mediaType = MediaType.EPUB.toString(),
                    coverPath = coverDestination
                        .takeIf { it.isFile && it.length() > 0L }
                        ?.absolutePath,
                ),
            )
        } catch (error: ImportRejectedException) {
            Outcome.Failure(error.rejection.toPublicationError())
        } catch (error: SourceReadException) {
            Outcome.Failure(
                PublicationError.SourceUnavailable(
                    "无法读取所选 EPUB，文件可能已移动或读取授权已失效。",
                    error,
                ),
            )
        } catch (error: SecurityException) {
            Outcome.Failure(
                PublicationError.SourceUnavailable(
                    "没有读取所选 EPUB 的权限。",
                    error,
                ),
            )
        } catch (error: IOException) {
            Outcome.Failure(
                PublicationError.PrivateStorageFailure(
                    "无法将 EPUB 复制到应用私有存储。",
                    error,
                ),
            )
        } finally {
            if (temporaryFile.exists()) temporaryFile.delete()
            if (temporaryCover.exists()) temporaryCover.delete()
        }
    }

    /** Opens a previously imported private EPUB and transfers publication ownership to the caller. */
    suspend fun openPrivateEpub(file: File): Outcome<OpenedEpub> = withContext(Dispatchers.IO) {
        if (!isPrivateBook(file)) {
            return@withContext Outcome.Failure(
                PublicationError.SourceUnavailable(
                    "阅读器只打开已经导入应用私有书库的 EPUB。",
                ),
            )
        }
        if (!file.isFile || !file.canRead()) {
            return@withContext Outcome.Failure(
                PublicationError.SourceUnavailable("私有 EPUB 文件缺失或不可读。"),
            )
        }

        val signature = EpubSignature.verify(file)
        if (!signature.isValid) {
            return@withContext Outcome.Failure(
                PublicationError.CorruptedEpub(
                    signature.message ?: "EPUB 压缩包已损坏。",
                ),
            )
        }

        when (val opened = openAndValidate(file)) {
            is Outcome.Success -> Outcome.Success(OpenedEpub(file, opened.value))
            is Outcome.Failure -> opened
        }
    }

    fun isPrivateBook(file: File): Boolean = try {
        val root = booksDirectory.canonicalFile.toPath()
        val candidate = file.canonicalFile.toPath()
        candidate.parent == root && candidate.fileName.toString().endsWith(".epub", ignoreCase = true)
    } catch (_: IOException) {
        false
    }

    fun isPrivateCover(file: File): Boolean = try {
        val root = coversDirectory.canonicalFile.toPath()
        val candidate = file.canonicalFile.toPath()
        candidate.parent == root && candidate.fileName.toString().endsWith(".jpg", ignoreCase = true)
    } catch (_: IOException) {
        false
    }

    /** Deletes only a cover owned by this manager. A missing cover is already considered clean. */
    fun deleteCover(file: File): Boolean =
        isPrivateCover(file) && (!file.exists() || file.delete())

    private data class PublicationMetadata(
        val title: String,
        val author: String?,
    )

    private suspend fun inspectEpub(
        file: File,
        temporaryCover: File,
    ): Outcome<PublicationMetadata> {
        return when (val opened = openAndValidate(file)) {
            is Outcome.Failure -> opened
            is Outcome.Success -> {
                val publication = opened.value
                try {
                    writeCoverIfAvailable(publication, temporaryCover)
                    Outcome.Success(
                        PublicationMetadata(
                            title = publication.metadata.title
                                ?.takeIf(String::isNotBlank)
                                ?: file.nameWithoutExtension,
                            author = publication.metadata.authors
                                .map { it.name.trim() }
                                .filter(String::isNotEmpty)
                                .joinToString()
                                .takeIf(String::isNotBlank),
                        ),
                    )
                } finally {
                    publication.close()
                }
            }
        }
    }

    private suspend fun writeCoverIfAvailable(
        publication: Publication,
        destination: File,
    ) {
        val bitmap = try {
            publication.coverFitting(COVER_MAX_SIZE)
        } catch (_: Exception) {
            null
        } ?: return

        try {
            FileOutputStream(destination).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, output)) {
                    throw IOException("Android could not encode the EPUB cover")
                }
                output.fd.sync()
            }
            if (destination.length() <= 0L) destination.delete()
        } catch (_: Exception) {
            destination.delete()
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun openAndValidate(file: File): Outcome<Publication> {
        val asset: Asset = when (val result = assetRetriever.retrieve(file, MediaType.EPUB)) {
            is Try.Success -> result.value
            is Try.Failure -> {
                return Outcome.Failure(
                    when (val error = result.value) {
                        is AssetRetriever.RetrieveError.FormatNotSupported ->
                            PublicationError.UnsupportedFormat()

                        is AssetRetriever.RetrieveError.Reading ->
                            PublicationError.CorruptedEpub(
                                "Readium 无法读取 EPUB 压缩包：${error.message}",
                            )
                    },
                )
            }
        }

        val publication = when (
            val result = publicationOpener.open(asset, allowUserInteraction = false)
        ) {
            is Try.Success -> result.value
            is Try.Failure -> {
                asset.close()
                return Outcome.Failure(
                    when (val error = result.value) {
                        is PublicationOpener.OpenError.FormatNotSupported ->
                            PublicationError.UnsupportedFormat()

                        is PublicationOpener.OpenError.Reading ->
                            PublicationError.CorruptedEpub(
                                "Readium 无法解析 EPUB：${error.message}",
                            )
                    },
                )
            }
        }

        if (!publication.conformsTo(Publication.Profile.EPUB)) {
            publication.close()
            return Outcome.Failure(PublicationError.UnsupportedFormat())
        }

        if (publication.isRestricted) {
            val detail = publication.protectionError?.message
            publication.close()
            return Outcome.Failure(
                PublicationError.RestrictedPublication(
                    detail?.let { "此 EPUB 受保护：$it" }
                        ?: "此 EPUB 受 DRM 保护，离线阅读器不支持。",
                ),
            )
        }

        val hasReadableText = try {
            publication.hasReadableText()
        } catch (error: Exception) {
            publication.close()
            return Outcome.Failure(
                PublicationError.CorruptedEpub(
                    "无法提取 EPUB 文本内容。",
                    error,
                ),
            )
        }

        if (!hasReadableText && publication.metadata.layout != Layout.FIXED) {
            publication.close()
            return Outcome.Failure(PublicationError.NoReadableText())
        }

        return Outcome.Success(publication)
    }

    private suspend fun Publication.hasReadableText(): Boolean {
        val iterator = content()?.iterator() ?: return false
        while (iterator.hasNext()) {
            val element = iterator.next()
            val text = (element as? Content.TextualElement)?.text.orEmpty()
            if (text.any(Char::isLetterOrDigit)) return true
        }
        return false
    }

    private fun copyUri(uri: Uri, destination: File) {
        rejectImportIfNeeded(
            EpubImportPolicy.beforeCopy(
                declaredSizeBytes = queryDocumentSize(uri),
                usableSpaceBytes = booksDirectory.usableSpace,
                limits = importLimits,
            ),
        )

        val input = try {
            applicationContext.contentResolver.openInputStream(uri)
        } catch (error: IOException) {
            throw SourceReadException(error)
        } ?: throw SourceReadException(IOException("Content resolver returned no stream for $uri"))

        var copiedBytes = 0L
        input.use { source ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val count = try {
                        source.read(buffer)
                    } catch (error: IOException) {
                        throw SourceReadException(error)
                    }
                    if (count < 0) break
                    if (count == 0) continue

                    rejectImportIfNeeded(
                        EpubImportPolicy.beforeWrite(
                            copiedBytes = copiedBytes,
                            nextChunkBytes = count,
                            usableSpaceBytes = booksDirectory.usableSpace,
                            limits = importLimits,
                        ),
                    )
                    output.write(buffer, 0, count)
                    copiedBytes += count
                }
                output.fd.sync()
            }
        }
        rejectImportIfNeeded(EpubImportPolicy.afterCopy(copiedBytes, importLimits))
    }

    /** Returns null when a provider omits SIZE, reports a negative sentinel, or cannot query it. */
    private fun queryDocumentSize(uri: Uri): Long? {
        val cursor = try {
            applicationContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        return try {
            cursor?.use {
                if (!it.moveToFirst()) return@use null
                val sizeColumn = it.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn < 0 || it.isNull(sizeColumn)) return@use null
                it.getLong(sizeColumn).takeIf { size -> size >= 0L }
            }
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun rejectImportIfNeeded(rejection: EpubImportRejection?) {
        if (rejection != null) throw ImportRejectedException(rejection)
    }

    private fun EpubImportRejection.toPublicationError(): PublicationError = when (this) {
        EpubImportRejection.EMPTY_SOURCE -> PublicationError.EmptyDocument()
        EpubImportRejection.FILE_TOO_LARGE -> PublicationError.ImportTooLarge(
            "所选 EPUB 超过 ${importLimits.maximumFileBytes.toMegabyteLabel()} 的导入上限。",
        )
        EpubImportRejection.INSUFFICIENT_FREE_SPACE -> PublicationError.InsufficientStorage(
            "可用存储空间不足；导入后需至少保留 ${importLimits.freeSpaceReserveBytes.toMegabyteLabel()}。",
        )
    }

    private fun Long.toMegabyteLabel(): String =
        if (this % BYTES_PER_MEBIBYTE == 0L) {
            "${this / BYTES_PER_MEBIBYTE} MB"
        } else {
            "$this 字节"
        }

    private class ImportRejectedException(
        val rejection: EpubImportRejection,
    ) : IOException(rejection.name)

    private class SourceReadException(cause: IOException) :
        IOException("Unable to read the selected document", cause)

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private companion object {
        const val BOOKS_DIRECTORY_NAME = "books"
        const val COVERS_DIRECTORY_NAME = "covers"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val COVER_JPEG_QUALITY = 88
        const val BYTES_PER_MEBIBYTE = 1024L * 1024L
        val COVER_MAX_SIZE = Size(1200, 1600)
    }
}

package com.tl2333.novelvoicereader.content.web

import com.tl2333.novelvoicereader.content.model.CanonicalDocument
import com.tl2333.novelvoicereader.content.model.CanonicalDocumentCodec
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class WebSnapshotEntity(
    val id: String,
    val url: String,
    val canonicalUrl: String,
    val title: String,
    val author: String?,
    val fetchedAt: Long,
    val contentHash: String,
    val htmlPath: String,
    val canonicalDocumentPath: String,
)

class WebSnapshotRepository(private val root: File) {
    fun create(
        requestedUrl: String,
        canonicalUrl: String,
        fetchedAt: Long,
        article: ExtractedArticle,
    ): WebSnapshotEntity {
        val directory = File(root, article.document.id).apply { mkdirs() }
        val html = File(directory, "content.html")
        val canonical = File(directory, "canonical.json")
        writeAtomically(html, article.sanitizedHtml.toByteArray(Charsets.UTF_8))
        CanonicalDocumentCodec.writeAtomically(article.document, canonical)
        val entity = WebSnapshotEntity(
            article.document.id,
            requestedUrl,
            canonicalUrl,
            article.document.title,
            article.document.author,
            fetchedAt,
            article.document.contentHash,
            html.absolutePath,
            canonical.absolutePath,
        )
        writeSnapshotMetadata(File(directory, "snapshot.json"), entity, "HTML")
        return entity
    }

    fun createDirect(
        requestedUrl: String,
        canonicalUrl: String,
        fetchedAt: Long,
        kind: WebContentKind,
        bytes: ByteArray,
        document: CanonicalDocument,
    ): WebSnapshotEntity {
        val directory = File(root, document.id).apply { mkdirs() }
        val content = File(directory, "content.${kind.name.lowercase()}")
        val canonical = File(directory, "canonical.json")
        writeAtomically(content, bytes)
        CanonicalDocumentCodec.writeAtomically(document, canonical)
        val entity = WebSnapshotEntity(
            document.id,
            requestedUrl,
            canonicalUrl,
            document.title,
            document.author,
            fetchedAt,
            document.contentHash,
            content.absolutePath,
            canonical.absolutePath,
        )
        writeSnapshotMetadata(File(directory, "snapshot.json"), entity, kind.name)
        return entity
    }

    fun createRawDownload(
        id: String,
        requestedUrl: String,
        canonicalUrl: String,
        fetchedAt: Long,
        kind: WebContentKind,
        bytes: ByteArray,
    ): File {
        val directory = File(root, id).apply { mkdirs() }
        val content = File(directory, "content.${kind.name.lowercase()}")
        writeAtomically(content, bytes)
        val metadata = buildJsonObject {
            put("id", id)
            put("url", requestedUrl)
            put("canonicalUrl", canonicalUrl)
            put("fetchedAt", fetchedAt)
            put("contentHash", id)
            put("kind", kind.name)
            put("contentPath", content.absolutePath)
        }.toString().toByteArray(Charsets.UTF_8)
        writeAtomically(File(directory, "snapshot.json"), metadata)
        return content
    }

    private fun writeSnapshotMetadata(file: File, entity: WebSnapshotEntity, kind: String) {
        val bytes = buildJsonObject {
            put("id", entity.id)
            put("url", entity.url)
            put("canonicalUrl", entity.canonicalUrl)
            put("title", entity.title)
            entity.author?.let { put("author", it) }
            put("fetchedAt", entity.fetchedAt)
            put("contentHash", entity.contentHash)
            put("htmlPath", entity.htmlPath)
            put("canonicalDocumentPath", entity.canonicalDocumentPath)
            put("kind", kind)
        }.toString().toByteArray(Charsets.UTF_8)
        writeAtomically(file, bytes)
    }

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.part")
        temporary.outputStream().buffered().use { output ->
            output.write(bytes)
            output.flush()
        }
        try {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    fun loadDocument(snapshot: WebSnapshotEntity): CanonicalDocument =
        CanonicalDocumentCodec.read(File(snapshot.canonicalDocumentPath))
}

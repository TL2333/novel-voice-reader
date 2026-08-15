package com.tl2333.novelvoicereader.content.importing

import com.tl2333.novelvoicereader.filesystem.ArchiveLimits
import com.tl2333.novelvoicereader.filesystem.SafeArchive
import java.io.File
import java.nio.charset.Charset

enum class DocumentContentKind { HTML, TXT, PDF, DOCX, EPUB, LEGACY_DOC, UNSUPPORTED }

object DocumentTypeDetector {
    private const val SNIFF_BYTES = 8 * 1024
    private val pdfMagic = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val oleMagic = byteArrayOf(
        0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte(),
    )
    private val zipMagic = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
    private val sniffArchiveLimits = ArchiveLimits(
        maxEntries = 20_000,
        maxSingleFileBytes = 2L * 1024 * 1024 * 1024,
        maxTotalBytes = 8L * 1024 * 1024 * 1024,
        maxCompressionRatio = 500.0,
        maxPathLength = 1_024,
        maxPathDepth = 64,
    )

    fun detectLocal(file: File, declaredMimeType: String?, displayName: String?): DocumentContentKind {
        if (!file.isFile || file.length() <= 0L) return DocumentContentKind.UNSUPPORTED
        val prefix = file.inputStream().buffered().use { input ->
            val buffer = ByteArray(SNIFF_BYTES)
            val count = input.read(buffer)
            if (count <= 0) byteArrayOf() else buffer.copyOf(count)
        }
        detectFixedMagic(prefix)?.let { fixed ->
            if (fixed != DocumentContentKind.UNSUPPORTED) return fixed
        }
        if (prefix.startsWith(zipMagic)) return detectLocalZip(file)
        return detectHintsAndText(declaredMimeType, displayName, prefix, allowHtml = false)
    }

    fun detectRemote(contentType: String?, bytes: ByteArray, urlPath: String = ""): DocumentContentKind {
        val prefix = bytes.copyOf(minOf(bytes.size, SNIFF_BYTES))
        detectFixedMagic(prefix)?.let { fixed ->
            if (fixed != DocumentContentKind.UNSUPPORTED) return fixed
        }
        if (prefix.startsWith(zipMagic)) return detectRemoteZip(bytes)
        return detectHintsAndText(contentType, urlPath, prefix, allowHtml = true)
    }

    private fun detectFixedMagic(prefix: ByteArray): DocumentContentKind? = when {
        prefix.startsWith(pdfMagic) -> DocumentContentKind.PDF
        prefix.startsWith(oleMagic) -> DocumentContentKind.LEGACY_DOC
        else -> null
    }

    private fun detectLocalZip(file: File): DocumentContentKind = runCatching {
        val inspection = SafeArchive.inspect(file, sniffArchiveLimits)
        val names = inspection.entries.asSequence().filterNot { it.isDirectory }.map { it.normalizedPath }.toSet()
        when {
            isEpub(names) && runCatching {
                SafeArchive.readEntryBytes(file, "mimetype", 128).toString(Charsets.US_ASCII).trim() == EPUB_MIME
            }.getOrDefault(false) -> DocumentContentKind.EPUB
            isDocx(names) -> DocumentContentKind.DOCX
            else -> DocumentContentKind.UNSUPPORTED
        }
    }.getOrDefault(DocumentContentKind.UNSUPPORTED)

    private fun detectRemoteZip(bytes: ByteArray): DocumentContentKind {
        val names = zipCentralDirectoryNames(bytes)
        return when {
            isEpub(names) && epubStoredMimetype(bytes) == EPUB_MIME -> DocumentContentKind.EPUB
            isDocx(names) -> DocumentContentKind.DOCX
            else -> DocumentContentKind.UNSUPPORTED
        }
    }

    private fun detectHintsAndText(
        declaredMimeType: String?,
        displayName: String?,
        prefix: ByteArray,
        allowHtml: Boolean,
    ): DocumentContentKind {
        val mime = declaredMimeType.orEmpty().substringBefore(';').trim().lowercase()
        val extension = displayName.orEmpty().substringBefore('?').substringAfterLast('.', "").lowercase()
        if (allowHtml && (mime.contains("html") || prefix.looksLikeHtml())) return DocumentContentKind.HTML
        return when {
            mime == "application/pdf" -> DocumentContentKind.PDF
            mime == "application/msword" -> DocumentContentKind.LEGACY_DOC
            mime.startsWith("text/plain") -> DocumentContentKind.TXT
            extension == "pdf" -> DocumentContentKind.PDF
            extension == "doc" -> DocumentContentKind.LEGACY_DOC
            extension in setOf("txt", "text") -> DocumentContentKind.TXT
            prefix.looksLikeText() -> DocumentContentKind.TXT
            else -> DocumentContentKind.UNSUPPORTED
        }
    }

    private fun isEpub(names: Set<String>): Boolean = "mimetype" in names && "META-INF/container.xml" in names

    private fun isDocx(names: Set<String>): Boolean =
        "[Content_Types].xml" in names && "word/document.xml" in names

    private fun epubStoredMimetype(bytes: ByteArray): String? {
        if (bytes.size < 30 || bytes.u16(8) != 0) return null
        val compressedSize = bytes.u32(18).toInt()
        val nameLength = bytes.u16(26)
        val extraLength = bytes.u16(28)
        val nameStart = 30
        val dataStart = nameStart + nameLength + extraLength
        if (nameLength !in 1..128 || compressedSize !in 1..128 || dataStart + compressedSize > bytes.size) return null
        val name = bytes.copyOfRange(nameStart, nameStart + nameLength).toString(Charsets.UTF_8)
        return if (name == "mimetype") bytes.copyOfRange(dataStart, dataStart + compressedSize).toString(Charsets.US_ASCII).trim() else null
    }

    private fun zipCentralDirectoryNames(bytes: ByteArray): Set<String> {
        val names = linkedSetOf<String>()
        var cursor = 0
        var entries = 0
        var nameBytes = 0
        while (cursor + 46 <= bytes.size && entries < 20_000 && nameBytes <= 2 * 1024 * 1024) {
            if (bytes.u32(cursor) != 0x02014b50L) {
                cursor++
                continue
            }
            val nameLength = bytes.u16(cursor + 28)
            val extraLength = bytes.u16(cursor + 30)
            val commentLength = bytes.u16(cursor + 32)
            val next = cursor + 46 + nameLength + extraLength + commentLength
            if (nameLength !in 1..1_024 || next > bytes.size) break
            val name = runCatching {
                bytes.copyOfRange(cursor + 46, cursor + 46 + nameLength).toString(Charsets.UTF_8)
            }.getOrNull() ?: break
            names += name.replace('\\', '/').removeSuffix("/")
            nameBytes += nameLength
            entries++
            cursor = next
        }
        return names
    }

    private fun ByteArray.looksLikeHtml(): Boolean {
        val ascii = toString(Charsets.ISO_8859_1).trimStart()
        return ascii.startsWith("<!doctype", true) || ascii.startsWith("<html", true)
    }

    private fun ByteArray.looksLikeText(): Boolean {
        if (isEmpty()) return false
        if (startsWith(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())) ||
            startsWith(byteArrayOf(0xff.toByte(), 0xfe.toByte())) ||
            startsWith(byteArrayOf(0xfe.toByte(), 0xff.toByte()))
        ) return true
        if (count { it == 0.toByte() } > size / 8) return looksLikeUtf16()
        if (any { value -> value.toInt() in 0..8 || value.toInt() in 14..31 }) return false
        return runCatching {
            Charset.forName("UTF-8").newDecoder().decode(java.nio.ByteBuffer.wrap(this))
            true
        }.getOrDefault(false) || count { (it.toInt() and 0xff) in 0x20..0x7e || it in listOf(9, 10, 13).map(Int::toByte) } >= size * 3 / 4
    }

    private fun ByteArray.looksLikeUtf16(): Boolean {
        val evenNulls = indices.count { it % 2 == 0 && this[it] == 0.toByte() }
        val oddNulls = indices.count { it % 2 == 1 && this[it] == 0.toByte() }
        return maxOf(evenNulls, oddNulls) >= size / 4
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.u16(offset: Int): Int =
        if (offset + 2 > size) -1 else (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.u32(offset: Int): Long =
        if (offset + 4 > size) -1L else (u16(offset).toLong() and 0xffff) or ((u16(offset + 2).toLong() and 0xffff) shl 16)

    private const val EPUB_MIME = "application/epub+zip"
}

object LocalContentTypeDetector {
    fun detect(file: File, declaredMimeType: String? = null, displayName: String? = null): DocumentContentKind =
        DocumentTypeDetector.detectLocal(file, declaredMimeType, displayName)
}

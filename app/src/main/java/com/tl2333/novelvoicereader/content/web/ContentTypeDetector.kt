package com.tl2333.novelvoicereader.content.web

enum class WebContentKind { HTML, TXT, PDF, DOCX, EPUB, UNSUPPORTED }

object ContentTypeDetector {
    fun detect(contentType: String?, bytes: ByteArray, urlPath: String = ""): WebContentKind {
        val type = contentType.orEmpty().substringBefore(';').trim().lowercase()
        val extension = urlPath.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when {
            bytes.startsWith("%PDF-") || type == "application/pdf" || extension == "pdf" -> WebContentKind.PDF
            bytes.isZip() && (type.contains("wordprocessingml") || extension == "docx") -> WebContentKind.DOCX
            bytes.isZip() && (type == "application/epub+zip" || extension == "epub") -> WebContentKind.EPUB
            type.startsWith("text/plain") || extension == "txt" -> WebContentKind.TXT
            type.contains("html") || bytes.asciiPrefix().contains("<html", true) || bytes.asciiPrefix().contains("<!doctype", true) -> WebContentKind.HTML
            else -> WebContentKind.UNSUPPORTED
        }
    }

    private fun ByteArray.startsWith(value: String): Boolean {
        val prefix = value.toByteArray(Charsets.US_ASCII)
        return size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }

    private fun ByteArray.isZip(): Boolean = size >= 4 && this[0] == 0x50.toByte() && this[1] == 0x4b.toByte() && this[2] == 0x03.toByte() && this[3] == 0x04.toByte()
    private fun ByteArray.asciiPrefix(): String = copyOfRange(0, minOf(size, 512)).toString(Charsets.ISO_8859_1)
}

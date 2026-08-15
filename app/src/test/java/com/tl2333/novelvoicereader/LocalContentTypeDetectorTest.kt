package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.content.importing.DocumentContentKind
import com.tl2333.novelvoicereader.content.importing.LocalContentTypeDetector
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalContentTypeDetectorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun magicOverridesMisleadingNameAndMime() {
        val pdf = temporary.newFile("not-a-pdf.txt").apply { writeBytes("%PDF-1.7\n".toByteArray()) }
        assertThat(LocalContentTypeDetector.detect(pdf, "text/plain", "wrong.txt"))
            .isEqualTo(DocumentContentKind.PDF)

        val doc = temporary.newFile("old.bin").apply {
            writeBytes(byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte()))
        }
        assertThat(LocalContentTypeDetector.detect(doc, "application/octet-stream", "old.bin"))
            .isEqualTo(DocumentContentKind.LEGACY_DOC)
    }

    @Test
    fun zipStructureDistinguishesEpubDocxAndUnknownZip() {
        assertThat(LocalContentTypeDetector.detect(zip("book.epub", mapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to "<container/>",
        )))).isEqualTo(DocumentContentKind.EPUB)

        assertThat(LocalContentTypeDetector.detect(zip("book.docx", mapOf(
            "[Content_Types].xml" to "<Types/>",
            "word/document.xml" to "<document/>",
        )))).isEqualTo(DocumentContentKind.DOCX)

        assertThat(LocalContentTypeDetector.detect(zip("fake.docx", mapOf("payload.bin" to "data")), null, "fake.docx"))
            .isEqualTo(DocumentContentKind.UNSUPPORTED)
    }

    @Test
    fun recognizesUtfBomAndPlainUtf8Fallback() {
        val utf16 = temporary.newFile("unknown.bin").apply {
            writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x41, 0x00))
        }
        assertThat(LocalContentTypeDetector.detect(utf16)).isEqualTo(DocumentContentKind.TXT)
        val utf8 = temporary.newFile("unknown.data").apply { writeText("A short readable paragraph.") }
        assertThat(LocalContentTypeDetector.detect(utf8)).isEqualTo(DocumentContentKind.TXT)

        val fakeDocx = temporary.newFile("fake.docx").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        assertThat(LocalContentTypeDetector.detect(fakeDocx, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "fake.docx"))
            .isEqualTo(DocumentContentKind.UNSUPPORTED)
    }

    private fun zip(name: String, entries: Map<String, String>): File = temporary.newFile(name).also { file ->
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (entryName, value) ->
                output.putNextEntry(ZipEntry(entryName))
                output.write(value.toByteArray())
                output.closeEntry()
            }
        }
    }
}

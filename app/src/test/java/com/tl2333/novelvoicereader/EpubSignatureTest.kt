package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.filesystem.EpubSignature
import com.tl2333.novelvoicereader.filesystem.EpubSignatureError
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubSignatureTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsEpubStructureAndRejectsMissingContainer() {
        val valid = epub(includeContainer = true)
        assertThat(EpubSignature.verify(valid).isValid).isTrue()

        val invalid = epub(includeContainer = false)
        val result = EpubSignature.verify(invalid)
        assertThat(result.isValid).isFalse()
        assertThat(result.error).isEqualTo(EpubSignatureError.MISSING_CONTAINER)
    }

    @Test
    fun rejectsExtensionOnlyZip() {
        val file = temporaryFolder.newFile("fake.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("book.txt"))
            zip.write("not epub".toByteArray())
            zip.closeEntry()
        }
        assertThat(EpubSignature.verify(file).error).isEqualTo(EpubSignatureError.MISSING_MIMETYPE)
    }

    private fun epub(includeContainer: Boolean): File {
        val file = temporaryFolder.newFile("book-${System.nanoTime()}.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write(EpubSignature.MIME_TYPE.toByteArray(Charsets.US_ASCII))
            zip.closeEntry()
            if (includeContainer) {
                zip.putNextEntry(ZipEntry("META-INF/container.xml"))
                zip.write("<container/>".toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }
}

package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.filesystem.ArchiveViolation
import com.tl2333.novelvoicereader.filesystem.SafeArchive
import com.tl2333.novelvoicereader.filesystem.UnsafeArchiveException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun inspectsAndAtomicallyExtractsSafeArchive() {
        val archive = createZip(mapOf("folder/hello.txt" to "hello".toByteArray()))
        val inspection = SafeArchive.inspect(archive)
        assertThat(inspection["folder/hello.txt"]?.sizeBytes).isEqualTo(5)

        val destination = File(temporaryFolder.root, "extracted")
        SafeArchive.extractAtomically(archive, destination)
        assertThat(File(destination, "folder/hello.txt").readText()).isEqualTo("hello")
    }

    @Test
    fun rejectsTraversalBeforeExtraction() {
        val archive = createZip(mapOf("../escape.txt" to "bad".toByteArray()))
        val error = assertThrows(UnsafeArchiveException::class.java) { SafeArchive.inspect(archive) }
        assertThat(error.violation).isEqualTo(ArchiveViolation.PATH_TRAVERSAL)
    }

    private fun createZip(entries: Map<String, ByteArray>): File {
        val file = temporaryFolder.newFile("archive-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}

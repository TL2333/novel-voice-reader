package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.filesystem.Sha256
import com.tl2333.novelvoicereader.tts.kokoro.KokoroModelManifest
import com.tl2333.novelvoicereader.tts.kokoro.KokoroModelVerifier
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KokoroModelManifestTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun parsesLockedManifestSchemaAndRejectsUnsafePaths() {
        val manifest = KokoroModelManifest.parse(manifestJson("model.int8.onnx", 3, "a".repeat(64)))
        assertThat(manifest.schemaVersion).isEqualTo(1)
        assertThat(manifest.huggingFaceCommit).isEqualTo(KokoroModelVerifier.PINNED_COMMIT)
        assertThat(manifest.file("model.int8.onnx")?.sizeBytes).isEqualTo(3)

        assertThrows(IllegalArgumentException::class.java) {
            KokoroModelManifest.parse(manifestJson("../model.int8.onnx", 3, "a".repeat(64)))
        }
    }

    @Test
    fun verifiesEveryDeclaredFileOnDisk() {
        val root = temporaryFolder.newFolder("model")
        val model = root.resolve("sample.bin").apply { writeBytes("abc".toByteArray()) }
        val manifest = KokoroModelManifest.parse(manifestJson("sample.bin", model.length(), Sha256.hash(model)))

        val valid = KokoroModelVerifier.verify(
            root,
            manifest,
            requiredPaths = setOf("sample.bin"),
            enforcePinnedCoreHashes = false,
        )
        assertThat(valid.isValid).isTrue()

        model.writeText("changed")
        val corrupt = KokoroModelVerifier.verify(
            root,
            manifest,
            requiredPaths = setOf("sample.bin"),
            enforcePinnedCoreHashes = false,
        )
        assertThat(corrupt.corruptPaths).contains("sample.bin")
    }

    private fun manifestJson(path: String, size: Long, sha256: String): String = """{
        "schemaVersion":1,
        "modelId":"kokoro-int8-multi-lang-v1_1",
        "modelVersion":"1.1",
        "huggingFaceCommit":"${KokoroModelVerifier.PINNED_COMMIT}",
        "sourceUrl":"https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_1",
        "archiveSha256":"${"b".repeat(64)}",
        "generatedAtUtc":"2026-08-03T00:00:00Z",
        "fileCount":1,
        "totalSizeBytes":$size,
        "files":[{"path":"$path","sizeBytes":$size,"sha256":"$sha256"}]
    }""".trimIndent()
}

package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class TtsProcessArchitectureTest {
    @Test
    fun sherpaImportsExistOnlyInTtsServiceLayer() {
        val main = locate("app/src/main")
        val violations = main.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .filter { file -> file.readText().contains("import com.k2fsa.sherpa.onnx") }
            .map { it.relativeTo(main).invariantSeparatorsPath }
            .filterNot { it.startsWith("java/com/tl2333/novelvoicereader/tts/service/") }
            .toList()
        assertThat(violations).isEmpty()
    }

    @Test
    fun manifestIsolatesInferenceInNamedProcess() {
        val manifest = locate("app/src/main/AndroidManifest.xml").readText()
        assertThat(manifest).contains(".tts.service.TtsInferenceService")
        assertThat(manifest).contains("android:process=\":tts\"")
        assertThat(manifest).contains("android:exported=\"false\"")
    }

    private fun locate(path: String): File = generateSequence(
        File(checkNotNull(System.getProperty("user.dir"))).absoluteFile,
    ) { it.parentFile }.map { File(it, path) }.firstOrNull(File::exists) ?: error("$path not found")
}

package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

class SherpaForbiddenCallbackApiTest {
    @Test
    fun productionSourcesUseNoCallbackGenerationApi() {
        val sourceRoot = locateSourceRoot()
        val forbidden = listOf("generateWithConfigAndCallback(", "generateWithCallback(")
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .flatMap { file -> file.useLines { lines -> lines.mapIndexedNotNull { index, line -> forbidden.firstOrNull(line::contains)?.let { "${file.relativeTo(sourceRoot)}:${index + 1}" } }.toList().asSequence() } }
            .toList()
        assertWithMessage("sherpa callback generation is forbidden in app/src/main").that(violations).isEmpty()
    }

    private fun locateSourceRoot(): File = generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main") }
        .firstOrNull(File::isDirectory)
        ?: error("app/src/main not found")
}

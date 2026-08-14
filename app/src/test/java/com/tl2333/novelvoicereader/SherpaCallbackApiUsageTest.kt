package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

class SherpaCallbackApiUsageTest {
    @Test
    fun productionSourcesDoNotInvokeSherpaCallbackApis() {
        val sourceRoot = locateProductionSourceRoot()
        val forbiddenInvocations = listOf(
            "generateWithConfigAndCallback(",
            "generateWithCallback(",
        )
        val violations = sourceRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension in setOf("kt", "java") }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    forbiddenInvocations.firstOrNull(line::contains)?.let { invocation ->
                        "${file.relativeTo(sourceRoot).invariantSeparatorsPath}:${index + 1}: $invocation"
                    }
                }
            }
            .toList()

        assertWithMessage(
            "sherpa-onnx callback JNI APIs crash on Android 15; production must use generateWithConfig",
        ).that(violations).isEmpty()
    }

    private fun locateProductionSourceRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
        return generateSequence(workingDirectory) { directory -> directory.parentFile }
            .flatMap { directory ->
                sequenceOf(
                    File(directory, "app/src/main/java"),
                    File(directory, "src/main/java"),
                )
            }
            .firstOrNull(File::isDirectory)
            ?: error("Could not locate app/src/main/java from $workingDirectory")
    }
}

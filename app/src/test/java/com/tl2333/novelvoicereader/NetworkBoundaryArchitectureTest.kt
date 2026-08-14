package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class NetworkBoundaryArchitectureTest {
    @Test
    fun networkAndRemoteWebViewImportsStayInsideContentWeb() {
        val main = locate("app/src/main")
        val forbiddenImports = listOf(
            "import java.net.HttpURLConnection",
            "import okhttp3.",
            "import android.webkit.WebView",
        )
        val violations = main.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .filter { file -> forbiddenImports.any(file.readText()::contains) }
            .map { it.relativeTo(main).invariantSeparatorsPath }
            .filterNot { it.startsWith("java/com/tl2333/novelvoicereader/content/web/") }
            .toList()
        assertThat(violations).isEmpty()
    }

    @Test
    fun manifestAllowsInternetButNoNetworkStateOrUploadSdk() {
        val manifest = locate("app/src/main/AndroidManifest.xml").readText()
        assertThat(manifest).contains("android.permission.INTERNET")
        assertThat(manifest).doesNotContain("android.permission.READ_PHONE_STATE")
    }

    private fun locate(path: String): File = generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .map { File(it, path) }.firstOrNull(File::exists) ?: error("$path not found")
}

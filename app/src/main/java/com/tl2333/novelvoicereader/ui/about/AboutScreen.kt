package com.tl2333.novelvoicereader.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tl2333.novelvoicereader.BuildConfig
import com.tl2333.novelvoicereader.ui.components.ScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LicenseItem(val component: String, val license: String, val asset: String)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialogTitle by remember { mutableStateOf<String?>(null) }
    var licenseText by remember { mutableStateOf("") }
    val licenses = remember {
        listOf(
            LicenseItem("Kokoro", "模型许可证", "licenses/KOKORO_LICENSE.txt"),
            LicenseItem("sherpa-onnx ${BuildConfig.SHERPA_ONNX_VERSION}", "Apache-2.0", "licenses/SHERPA_ONNX_LICENSE.txt"),
            LicenseItem("ONNX Runtime", "MIT", "licenses/ONNX_RUNTIME_LICENSE.txt"),
            LicenseItem("Readium ${BuildConfig.READIUM_VERSION}", "BSD-3-Clause", "licenses/READIUM_LICENSE.txt"),
            LicenseItem("AndroidX / Media3", "第三方声明", "licenses/ANDROIDX_MEDIA3_NOTICES.txt"),
        )
    }

    ScreenScaffold(title = "关于与开源许可证", onBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Novel Voice Reader ${BuildConfig.VERSION_NAME}")
            Text("本应用只处理本地 EPUB，不上传小说内容，不包含远程日志或用户账户。")
            Text("内置声音是模型提供的合成声音；本应用不提供真人声音克隆功能。")
            licenses.forEach { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.component)
                        Text(item.license)
                        Button(onClick = {
                            scope.launch {
                                licenseText = withContext(Dispatchers.IO) {
                                    runCatching {
                                        context.assets.open(item.asset).bufferedReader().use { it.readText() }
                                    }.getOrElse { "许可证文件无法读取：${it.message.orEmpty()}" }
                                }
                                dialogTitle = item.component
                            }
                        }) { Text("查看许可证") }
                    }
                }
            }
        }
    }

    if (dialogTitle != null) {
        AlertDialog(
            onDismissRequest = { dialogTitle = null },
            title = { Text(requireNotNull(dialogTitle)) },
            text = {
                Text(
                    licenseText,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { dialogTitle = null }) { Text("关闭") } },
        )
    }
}

package com.tl2333.novelvoicereader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.tl2333.novelvoicereader.app.AppNavigation
import com.tl2333.novelvoicereader.app.AppContainer
import com.tl2333.novelvoicereader.content.importing.ExternalImportIntentRouter
import com.tl2333.novelvoicereader.content.importing.ExternalIntentEnvelope
import com.tl2333.novelvoicereader.content.importing.ExternalIntentRoute
import com.tl2333.novelvoicereader.content.importing.ImportIntentDeduplicator
import com.tl2333.novelvoicereader.ui.theme.NovelVoiceTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer
    private lateinit var importDeduplicator: ImportIntentDeduplicator

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Denial is non-fatal: foreground narration remains usable while notification UI is limited.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as NovelVoiceApplication).container
        importDeduplicator = ImportIntentDeduplicator(
            savedInstanceState?.getStringArrayList(STATE_PROCESSED_IMPORTS).orEmpty(),
        )
        setContent {
            NovelVoiceTheme {
                AppNavigation(container)
            }
        }
        handleExternalIntent(intent)
        if (
            savedInstanceState == null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(STATE_PROCESSED_IMPORTS, importDeduplicator.snapshot())
        super.onSaveInstanceState(outState)
    }

    private fun handleExternalIntent(intent: Intent?) {
        val source = intent ?: return
        val routed = ExternalImportIntentRouter.route(
            ExternalIntentEnvelope(
                action = source.action,
                dataUri = source.data?.toString(),
                streamUri = source.streamUri()?.toString(),
                mimeType = source.type,
            ),
        )
        when (routed) {
            ExternalIntentRoute.Ignore -> Unit
            is ExternalIntentRoute.Unsupported -> Toast.makeText(this, routed.reason, Toast.LENGTH_LONG).show()
            is ExternalIntentRoute.Import -> {
                if (!importDeduplicator.accept(routed.deduplicationKey)) return
                lifecycleScope.launch {
                    val result = container.import(routed.request)
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM)
    }

    private companion object {
        const val STATE_PROCESSED_IMPORTS = "processed-external-imports"
    }
}

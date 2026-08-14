@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.tl2333.novelvoicereader.debug

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.tl2333.novelvoicereader.NovelVoiceApplication
import com.tl2333.novelvoicereader.reader.NarrationCreationResult
import com.tl2333.novelvoicereader.reader.PublicationManager
import com.tl2333.novelvoicereader.reader.ReaderNarrationNavigator
import com.tl2333.novelvoicereader.ui.diagnostics.DiagnosticsActionResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication

/** ADB-launchable, debug-only real-device smoke harness. It is never part of release builds. */
class DeviceSmokeActivity : ComponentActivity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            text = "RUNNING"
        }
        setContentView(status)
        lifecycleScope.launch { runSmoke() }
    }

    private suspend fun runSmoke() {
        var publication: Publication? = null
        var navigator: ReaderNarrationNavigator? = null
        try {
            report("DIAGNOSTIC_RUNNING")
            val application = application as NovelVoiceApplication
            val container = application.container
            val diagnostic = container.diagnosticsController.generateTestSpeech()
            check(diagnostic is DiagnosticsActionResult.Completed) {
                "Diagnostic rejected: $diagnostic"
            }
            report("DIAGNOSTIC_PASSED")

            val imported = container.importBuiltInTestBook()
            check(imported.successful && !imported.bookId.isNullOrBlank()) {
                "Built-in EPUB import failed: ${imported.message}"
            }
            val bookId = requireNotNull(imported.bookId)
            val book = requireNotNull(container.books.get(bookId))
            check(container.clearBookCache(bookId).successful) { "Unable to clear built-in test-book cache" }
            val baseline = narrationWavPaths()

            val opened = container.publicationManager.openPrivateEpub(File(book.epubPath))
            publication = when (opened) {
                is PublicationManager.Outcome.Success -> opened.value.publication
                is PublicationManager.Outcome.Failure -> error(opened.error.userMessage)
            }
            navigator = createNavigator(application, bookId, requireNotNull(publication))
            report("EPUB_NARRATION_RUNNING")
            navigator.play()
            waitForNewNarrationFiles(baseline, requiredCount = 5)
            report("EPUB_FIVE_SENTENCES_PASSED")

            navigator.pause()
            delay(INTERACTION_DELAY_MILLIS)
            navigator.skipToNextUtterance()
            delay(INTERACTION_DELAY_MILLIS)
            navigator.asMedia3Player().stop()
            delay(INTERACTION_DELAY_MILLIS)
            navigator.play()
            delay(RESTART_OBSERVATION_MILLIS)
            navigator.close()
            navigator = null
            publication.close()
            publication = null

            val reopened = container.publicationManager.openPrivateEpub(File(book.epubPath))
            publication = when (reopened) {
                is PublicationManager.Outcome.Success -> reopened.value.publication
                is PublicationManager.Outcome.Failure -> error(reopened.error.userMessage)
            }
            navigator = createNavigator(application, bookId, requireNotNull(publication))
            navigator.play()
            delay(RESTART_OBSERVATION_MILLIS)
            report("PASS")
        } catch (error: Throwable) {
            Log.e(TAG, "FAIL", error)
            writeResult("FAIL\n${error.stackTraceToString()}")
            status.text = "FAIL: ${error.message}"
        } finally {
            runCatching { navigator?.close() }
            runCatching { publication?.close() }
        }
    }

    private suspend fun createNavigator(
        application: NovelVoiceApplication,
        bookId: String,
        publication: Publication,
    ): ReaderNarrationNavigator {
        val factory = requireNotNull(application.container.readerDependencies.narrationFactory)
        return when (val result = factory.create(bookId, publication, null) {}) {
            is NarrationCreationResult.Success -> result.navigator
            is NarrationCreationResult.Failure -> error(result.message)
        }
    }

    private suspend fun waitForNewNarrationFiles(before: Set<String>, requiredCount: Int) {
        val deadline = System.currentTimeMillis() + NATIVE_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val created = narrationWavPaths() - before
            if (created.size >= requiredCount) return
            delay(CACHE_POLL_MILLIS)
        }
        val created = narrationWavPaths() - before
        error("Expected $requiredCount new narration WAV files, found ${created.size}: $created")
    }

    private suspend fun narrationWavPaths(): Set<String> = withContext(Dispatchers.IO) {
        val root = File(cacheDir, "narration")
        if (!root.isDirectory) return@withContext emptySet()
        root.walkTopDown()
            .filter { file -> file.isFile && file.extension.equals("wav", ignoreCase = true) }
            .map(File::getAbsolutePath)
            .toSet()
    }

    private fun report(step: String) {
        Log.i(TAG, step)
        writeResult(step)
        status.text = step
    }

    private fun writeResult(value: String) {
        File(filesDir, RESULT_FILE).writeText(value, Charsets.UTF_8)
    }

    private companion object {
        const val TAG = "NVR_DEVICE_SMOKE"
        const val RESULT_FILE = "device-smoke-result.txt"
        const val CACHE_POLL_MILLIS = 2_000L
        const val INTERACTION_DELAY_MILLIS = 1_000L
        const val RESTART_OBSERVATION_MILLIS = 5_000L
        const val NATIVE_TIMEOUT_MILLIS = 900_000L
    }
}

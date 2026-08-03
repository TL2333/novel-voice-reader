@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.tl2333.novelvoicereader.playback

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tl2333.novelvoicereader.data.database.ReadingProgressRepository
import com.tl2333.novelvoicereader.reader.ReaderActivity
import com.tl2333.novelvoicereader.reader.ReaderNarrationNavigator
import com.tl2333.novelvoicereader.reader.ReaderNarrationSession
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Media3 service owning the active Readium TTS navigator during background playback. */
class NarrationMediaSessionService : MediaSessionService() {

    private data class ActiveSession(
        val bookId: String,
        val navigator: ReaderNarrationNavigator,
        val mediaSession: MediaSession,
        val progressWriter: NarrationProgressWriter?,
        val progressJob: Job?,
    )

    inner class LocalBinder : Binder() {
        suspend fun openSession(
            bookId: String,
            navigator: ReaderNarrationNavigator,
            readingProgressRepository: ReadingProgressRepository?,
        ) {
            enforceAppCaller()
            closeSessionInternal()

            val activityIntent = ReaderActivity.createIntent(
                context = applicationContext,
                bookId = bookId,
            ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                bookId.hashCode(),
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val mediaSession = MediaSession.Builder(
                applicationContext,
                navigator.asMedia3Player(),
            )
                .setId(bookId)
                .setSessionActivity(pendingIntent)
                .build()

            addSession(mediaSession)
            val progressWriter = readingProgressRepository?.let { repository ->
                NarrationProgressWriter(bookId) { savedBookId, locatorJson, totalProgression ->
                    repository.save(savedBookId, locatorJson, totalProgression)
                }.also { writer ->
                    // Capture the StateFlow's value synchronously so even an immediate service
                    // shutdown can flush the full locator.
                    writer.offer(navigator.currentLocator.value)
                }
            }
            val progressJob = progressWriter?.let { writer ->
                navigator.currentLocator
                    .onEach { locator -> writer.offer(locator) }
                    .debounce(PROGRESS_SAVE_DEBOUNCE_MS)
                    .onEach {
                        try {
                            writer.flushLatest()
                        } catch (error: Exception) {
                            Log.e(TAG, "Could not persist narration progress", error)
                        }
                    }
                    .catch { error ->
                        Log.e(TAG, "Narration progress observer failed", error)
                    }
                    .launchIn(progressScope)
            }
            activeSession = ActiveSession(
                bookId = bookId,
                navigator = navigator,
                mediaSession = mediaSession,
                progressWriter = progressWriter,
                progressJob = progressJob,
            )
        }

        suspend fun closeSession() {
            enforceAppCaller()
            closeSessionInternal()
        }

        private fun enforceAppCaller() {
            check(Binder.getCallingUid() == applicationInfo.uid) {
                "The narration service's local interface is app-private"
            }
        }
    }

    private val localBinder = LocalBinder()
    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeSession: ActiveSession? = null

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == LOCAL_INTERFACE_ACTION) {
            // MediaSessionService performs bookkeeping in its own onBind implementation.
            super.onBind(intent)
            localBinder
        } else {
            super.onBind(intent)
        }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        activeSession?.mediaSession

    override fun onDestroy() {
        // onDestroy cannot suspend, but the final Room write must finish before Android tears the
        // service down. Progress collection runs on Dispatchers.Default, avoiding a Main deadlock.
        runBlocking { closeSessionInternal() }
        progressScope.cancel()
        super.onDestroy()
    }

    private suspend fun closeSessionInternal() {
        val session = activeSession ?: return
        activeSession = null

        // Once teardown starts, finish the final durable write and native/session cleanup even if
        // the Activity-side caller is concurrently cancelled.
        withContext(NonCancellable) {
            session.progressJob?.cancel()
            try {
                session.progressWriter?.let { writer ->
                    writer.offer(session.navigator.currentLocator.value)
                    writer.flushLatest()
                }
            } catch (error: Exception) {
                // A database failure must not leak the MediaSession or native TTS navigator.
                Log.e(TAG, "Could not flush narration progress during shutdown", error)
            } finally {
                try {
                    session.mediaSession.release()
                } finally {
                    session.navigator.close()
                }
            }
        }
    }

    companion object {
        private const val TAG = "NarrationMediaService"
        private const val PROGRESS_SAVE_DEBOUNCE_MS = 750L
        const val LOCAL_INTERFACE_ACTION =
            "com.tl2333.novelvoicereader.playback.LOCAL_NARRATION_SESSION"

        internal fun intent(context: Context): Intent =
            Intent(LOCAL_INTERFACE_ACTION).setClass(
                context,
                NarrationMediaSessionService::class.java,
            )
    }
}

/** In-process client used by [ReaderNarrationSession] to transfer navigator ownership to Media3. */
class NarrationMediaServiceClient(
    application: Application,
    private val readingProgressRepository: ReadingProgressRepository? = null,
) : ReaderNarrationSession {
    private val application = application.applicationContext
    private val mutex = Mutex()
    private var binder: NarrationMediaSessionService.LocalBinder? = null
    private var connection: ServiceConnection? = null

    override suspend fun open(bookId: String, navigator: ReaderNarrationNavigator) {
        mutex.withLock {
            try {
                if (binder == null && connection != null) {
                    releaseBinding(stopService = false)
                }
                val serviceBinder = binder ?: bind().also { binder = it }
                withContext(Dispatchers.Main.immediate) {
                    serviceBinder.openSession(bookId, navigator, readingProgressRepository)
                }
            } catch (error: Exception) {
                try {
                    releaseBinding(stopService = true)
                } catch (_: Exception) {
                    // Preserve the original session-opening failure.
                }
                throw error
            }
        }
    }

    override suspend fun close() {
        mutex.withLock {
            try {
                withContext(Dispatchers.Main.immediate) {
                    binder?.closeSession()
                }
            } finally {
                releaseBinding(stopService = true)
            }
        }
    }

    private suspend fun bind(): NarrationMediaSessionService.LocalBinder =
        withContext(Dispatchers.Main.immediate) {
            application.startService(NarrationMediaSessionService.intent(application))
            suspendCancellableCoroutine { continuation ->
                val serviceConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        if (continuation.isActive) {
                            continuation.resume(service as NarrationMediaSessionService.LocalBinder)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        binder = null
                    }

                    override fun onNullBinding(name: ComponentName) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException("Narration service returned a null binding"),
                            )
                        }
                    }
                }
                connection = serviceConnection
                val bound = application.bindService(
                    NarrationMediaSessionService.intent(application),
                    serviceConnection,
                    Context.BIND_AUTO_CREATE,
                )
                if (!bound && continuation.isActive) {
                    connection = null
                    continuation.resumeWithException(
                        IllegalStateException("Could not bind to the narration service"),
                    )
                }
                continuation.invokeOnCancellation {
                    if (connection === serviceConnection) {
                        runCatching { application.unbindService(serviceConnection) }
                        connection = null
                    }
                }
            }
        }

    private suspend fun releaseBinding(stopService: Boolean) {
        withContext(Dispatchers.Main.immediate) {
            connection?.let { serviceConnection ->
                runCatching { application.unbindService(serviceConnection) }
            }
            connection = null
            binder = null
            if (stopService) {
                application.stopService(NarrationMediaSessionService.intent(application))
            }
        }
    }
}

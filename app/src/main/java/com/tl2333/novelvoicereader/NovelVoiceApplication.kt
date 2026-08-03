package com.tl2333.novelvoicereader

import android.app.Application
import com.tl2333.novelvoicereader.app.AppContainer
import com.tl2333.novelvoicereader.app.KokoroIntegration
import com.tl2333.novelvoicereader.data.preferences.ReaderTtsPreferencesStore
import com.tl2333.novelvoicereader.reader.ReadiumTtsNarrationFactory
import com.tl2333.novelvoicereader.reader.ReaderDependencies
import com.tl2333.novelvoicereader.reader.ReaderDependenciesOwner
import com.tl2333.novelvoicereader.tts.diagnostics.KokoroDiagnosticController
import com.tl2333.novelvoicereader.tts.kokoro.KokoroTtsPreferences
import com.tl2333.novelvoicereader.tts.kokoro.KokoroTtsEngineProvider
import kotlinx.coroutines.flow.first

class NovelVoiceApplication : Application(), ReaderDependenciesOwner {
    lateinit var container: AppContainer
        private set

    override val readerDependencies: ReaderDependencies
        get() = container.readerDependencies

    override fun onCreate() {
        super.onCreate()
        val preferencesStore = ReaderTtsPreferencesStore(this)
        val kokoroProvider = KokoroTtsEngineProvider(
            context = this,
            cacheMaxBytes = { preferencesStore.preferences.first().cacheLimitBytes },
        )
        container = AppContainer(
            application = this,
            kokoroIntegration = KokoroIntegration(
                narrationFactory = ReadiumTtsNarrationFactory(
                    application = this,
                    ttsEngineProviderFactory = kokoroProvider::forBook,
                    initialPreferences = {
                        val preferences = preferencesStore.preferences.first()
                        KokoroTtsPreferences(
                            voiceSid = preferences.defaultVoiceSid,
                            speed = preferences.narrationSpeed.toDouble(),
                            style = preferences.narrationStyle,
                            autoStyle = preferences.automaticStyle,
                        )
                    },
                ),
                diagnosticsController = KokoroDiagnosticController(
                    application = this,
                    provider = kokoroProvider,
                    preferencesStore = preferencesStore,
                ),
            ),
            preferencesStore = preferencesStore,
        )
    }
}

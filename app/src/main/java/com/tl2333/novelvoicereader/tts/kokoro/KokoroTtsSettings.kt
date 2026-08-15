package com.tl2333.novelvoicereader.tts.kokoro

import com.tl2333.novelvoicereader.tts.tokenizer.NarrationStyle
import org.readium.navigator.media.tts.TtsEngine
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.util.Language

@OptIn(ExperimentalReadiumApi::class)
data class KokoroTtsSettings(
    override val language: Language,
    override val overrideContentLanguage: Boolean,
    val effectiveVoiceSid: Int,
    val effectiveSpeed: Double,
    val effectiveStyle: NarrationStyle,
    val autoStyle: Boolean,
) : TtsEngine.Settings

data class KokoroTtsDefaults(
    val language: Language = Language("zh-Hans"),
    val voiceSid: Int = KokoroVoiceCatalog.DEFAULT_FEMALE_SID,
    val speed: Double = 1.0,
    val style: NarrationStyle = NarrationStyle.NEUTRAL,
    val autoStyle: Boolean = false,
) {
    init {
        require(KokoroVoiceCatalog.bySid(voiceSid) != null)
        require(speed in KokoroTtsPreferences.MIN_SPEED..KokoroTtsPreferences.MAX_SPEED)
    }
}

internal class KokoroTtsSettingsResolver(
    private val metadata: Metadata,
    private val defaults: KokoroTtsDefaults,
) {
    fun settings(preferences: KokoroTtsPreferences): KokoroTtsSettings =
        KokoroTtsSettings(
            language = preferences.language ?: metadata.language ?: defaults.language,
            overrideContentLanguage = preferences.language != null,
            effectiveVoiceSid = preferences.voiceSid ?: defaults.voiceSid,
            effectiveSpeed = preferences.speed ?: defaults.speed,
            effectiveStyle = preferences.style ?: defaults.style,
            autoStyle = preferences.autoStyle ?: defaults.autoStyle,
        )
}

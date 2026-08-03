package com.tl2333.novelvoicereader.tts.kokoro

import com.tl2333.novelvoicereader.tts.tokenizer.NarrationStyle
import org.readium.navigator.media.tts.TtsEngine
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.Language

@OptIn(ExperimentalReadiumApi::class)
data class KokoroTtsPreferences(
    override val language: Language? = null,
    val voiceSid: Int? = null,
    val speed: Double? = null,
    val style: NarrationStyle? = null,
    val autoStyle: Boolean? = null,
) : TtsEngine.Preferences<KokoroTtsPreferences> {
    init {
        require(voiceSid == null || KokoroVoiceCatalog.bySid(voiceSid) != null) {
            "Kokoro voice sid must be in the official Chinese voice catalog"
        }
        require(speed == null || speed in MIN_SPEED..MAX_SPEED) {
            "Kokoro speed must be between $MIN_SPEED and $MAX_SPEED"
        }
    }

    override fun plus(other: KokoroTtsPreferences): KokoroTtsPreferences =
        KokoroTtsPreferences(
            language = other.language ?: language,
            voiceSid = other.voiceSid ?: voiceSid,
            speed = other.speed ?: speed,
            style = other.style ?: style,
            autoStyle = other.autoStyle ?: autoStyle,
        )

    companion object {
        const val MIN_SPEED: Double = 0.5
        const val MAX_SPEED: Double = 2.0
    }
}

@file:OptIn(org.readium.r2.shared.InternalReadiumApi::class)

package com.tl2333.novelvoicereader.tts.kokoro

import com.tl2333.novelvoicereader.tts.tokenizer.NarrationStyle
import org.readium.r2.navigator.extensions.format
import org.readium.r2.navigator.preferences.DoubleIncrement
import org.readium.r2.navigator.preferences.IntIncrement
import org.readium.r2.navigator.preferences.Preference
import org.readium.r2.navigator.preferences.PreferenceDelegate
import org.readium.r2.navigator.preferences.PreferencesEditor
import org.readium.r2.navigator.preferences.RangePreference
import org.readium.r2.navigator.preferences.RangePreferenceDelegate
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.util.Language

@OptIn(ExperimentalReadiumApi::class)
class KokoroTtsPreferencesEditor(
    initialPreferences: KokoroTtsPreferences,
    publicationMetadata: Metadata,
    defaults: KokoroTtsDefaults,
) : PreferencesEditor<KokoroTtsPreferences> {
    private data class State(
        val preferences: KokoroTtsPreferences,
        val settings: KokoroTtsSettings,
    )

    private val settingsResolver = KokoroTtsSettingsResolver(publicationMetadata, defaults)
    private var state = initialPreferences.toState()

    override val preferences: KokoroTtsPreferences
        get() = state.preferences

    override fun clear() {
        update { KokoroTtsPreferences() }
    }

    val language: Preference<Language?> = PreferenceDelegate(
        getValue = { preferences.language },
        getEffectiveValue = { state.settings.language },
        getIsEffective = { true },
        updateValue = { value -> update { it.copy(language = value) } },
    )

    val voiceSid: RangePreference<Int> = RangePreferenceDelegate(
        getValue = { preferences.voiceSid },
        getEffectiveValue = { state.settings.effectiveVoiceSid },
        getIsEffective = { true },
        updateValue = { value -> update { it.copy(voiceSid = value) } },
        supportedRange = 3..102,
        progressionStrategy = IntIncrement(1),
        valueFormatter = { sid -> KokoroVoiceCatalog.requireBySid(sid).displayName },
    )

    val speed: RangePreference<Double> = RangePreferenceDelegate(
        getValue = { preferences.speed },
        getEffectiveValue = { state.settings.effectiveSpeed },
        getIsEffective = { true },
        updateValue = { value -> update { it.copy(speed = value) } },
        supportedRange = KokoroTtsPreferences.MIN_SPEED..KokoroTtsPreferences.MAX_SPEED,
        progressionStrategy = DoubleIncrement(0.05),
        valueFormatter = { "${it.format(2)}x" },
    )

    val style: Preference<NarrationStyle> = PreferenceDelegate(
        getValue = { preferences.style },
        getEffectiveValue = { state.settings.effectiveStyle },
        getIsEffective = { true },
        updateValue = { value -> update { it.copy(style = value) } },
    )

    val autoStyle: Preference<Boolean> = PreferenceDelegate(
        getValue = { preferences.autoStyle },
        getEffectiveValue = { state.settings.autoStyle },
        getIsEffective = { true },
        updateValue = { value -> update { it.copy(autoStyle = value) } },
    )

    private fun update(updater: (KokoroTtsPreferences) -> KokoroTtsPreferences) {
        state = updater(preferences).toState()
    }

    private fun KokoroTtsPreferences.toState() =
        State(this, settingsResolver.settings(this))
}

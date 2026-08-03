package com.tl2333.novelvoicereader.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.tl2333.novelvoicereader.tts.tokenizer.NarrationStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ReaderTheme { DAY, NIGHT, EYE_CARE }
enum class ReaderNavigationMode { SCROLL, PAGINATED }
enum class ReaderTextAlignment { PUBLISHER, START, JUSTIFY }

data class ReaderTtsPreferences(
    val readerTheme: ReaderTheme = ReaderTheme.DAY,
    val fontSizeSp: Float = 18f,
    val lineSpacing: Float = 1.6f,
    val pageMarginDp: Float = 20f,
    val paragraphSpacing: Float = 0f,
    val textAlignment: ReaderTextAlignment = ReaderTextAlignment.PUBLISHER,
    /** -1 uses Android's system brightness; otherwise this is a fraction in 0.05..1.0. */
    val screenBrightness: Float = -1f,
    val keepScreenOn: Boolean = false,
    val navigationMode: ReaderNavigationMode = ReaderNavigationMode.SCROLL,
    val defaultVoiceSid: Int = 3,
    val narrationSpeed: Float = 1f,
    val narrationStyle: NarrationStyle = NarrationStyle.NEUTRAL,
    val automaticStyle: Boolean = true,
    val automaticFollow: Boolean = true,
    val cacheLimitBytes: Long = 512L * 1024 * 1024,
)

class ReaderTtsPreferencesStore(
    context: Context,
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create {
        context.applicationContext.preferencesDataStoreFile(FILE_NAME)
    },
) {
    val preferences: Flow<ReaderTtsPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map(::decode)

    suspend fun updateReader(
        theme: ReaderTheme,
        fontSizeSp: Float,
        lineSpacing: Float,
        pageMarginDp: Float,
        navigationMode: ReaderNavigationMode,
    ) {
        dataStore.edit {
            it[Keys.theme] = theme.name
            it[Keys.fontSize] = fontSizeSp.coerceIn(12f, 48f)
            it[Keys.lineSpacing] = lineSpacing.coerceIn(1f, 3f)
            it[Keys.pageMargin] = pageMarginDp.coerceIn(0f, 64f)
            it[Keys.navigation] = navigationMode.name
        }
    }

    suspend fun updateNarration(
        voiceSid: Int,
        speed: Float,
        style: NarrationStyle,
        automaticStyle: Boolean,
        automaticFollow: Boolean,
    ) {
        dataStore.edit {
            it[Keys.voiceSid] = voiceSid.coerceIn(3, 102)
            it[Keys.speed] = speed.coerceIn(0.5f, 2f)
            it[Keys.style] = style.name
            it[Keys.autoStyle] = automaticStyle
            it[Keys.autoFollow] = automaticFollow
        }
    }

    suspend fun updateAdvancedReader(
        paragraphSpacing: Float,
        textAlignment: ReaderTextAlignment,
        screenBrightness: Float,
        keepScreenOn: Boolean,
    ) {
        dataStore.edit {
            it[Keys.paragraphSpacing] = paragraphSpacing.coerceIn(0f, 2f)
            it[Keys.textAlignment] = textAlignment.name
            it[Keys.screenBrightness] = normalizeBrightness(screenBrightness)
            it[Keys.keepScreenOn] = keepScreenOn
        }
    }

    suspend fun setCacheLimit(bytes: Long) {
        dataStore.edit { it[Keys.cacheLimit] = bytes.coerceAtLeast(0L) }
    }

    suspend fun reset() {
        dataStore.edit { it.clear() }
    }

    private fun decode(values: Preferences): ReaderTtsPreferences = ReaderTtsPreferences(
        readerTheme = values[Keys.theme].enumOrDefault(ReaderTheme.DAY),
        fontSizeSp = (values[Keys.fontSize] ?: 18f).coerceIn(12f, 48f),
        lineSpacing = (values[Keys.lineSpacing] ?: 1.6f).coerceIn(1f, 3f),
        pageMarginDp = (values[Keys.pageMargin] ?: 20f).coerceIn(0f, 64f),
        paragraphSpacing = (values[Keys.paragraphSpacing] ?: 0f).coerceIn(0f, 2f),
        textAlignment = values[Keys.textAlignment].enumOrDefault(ReaderTextAlignment.PUBLISHER),
        screenBrightness = normalizeBrightness(values[Keys.screenBrightness] ?: -1f),
        keepScreenOn = values[Keys.keepScreenOn] ?: false,
        navigationMode = values[Keys.navigation].enumOrDefault(ReaderNavigationMode.SCROLL),
        defaultVoiceSid = (values[Keys.voiceSid] ?: 3).coerceIn(3, 102),
        narrationSpeed = (values[Keys.speed] ?: 1f).coerceIn(0.5f, 2f),
        narrationStyle = values[Keys.style].enumOrDefault(NarrationStyle.NEUTRAL),
        automaticStyle = values[Keys.autoStyle] ?: true,
        automaticFollow = values[Keys.autoFollow] ?: true,
        cacheLimitBytes = (values[Keys.cacheLimit] ?: 512L * 1024 * 1024).coerceAtLeast(0L),
    )

    private object Keys {
        val theme = stringPreferencesKey("reader_theme")
        val fontSize = floatPreferencesKey("font_size_sp")
        val lineSpacing = floatPreferencesKey("line_spacing")
        val pageMargin = floatPreferencesKey("page_margin_dp")
        val paragraphSpacing = floatPreferencesKey("paragraph_spacing")
        val textAlignment = stringPreferencesKey("text_alignment")
        val screenBrightness = floatPreferencesKey("screen_brightness")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val navigation = stringPreferencesKey("navigation_mode")
        val voiceSid = intPreferencesKey("voice_sid")
        val speed = floatPreferencesKey("narration_speed")
        val style = stringPreferencesKey("narration_style")
        val autoStyle = booleanPreferencesKey("automatic_style")
        val autoFollow = booleanPreferencesKey("automatic_follow")
        val cacheLimit = longPreferencesKey("cache_limit_bytes")
    }

    companion object {
        const val FILE_NAME = "reader-tts.preferences_pb"
    }
}

private fun normalizeBrightness(value: Float): Float =
    if (value < 0f) -1f else value.coerceIn(0.05f, 1f)

private inline fun <reified T : Enum<T>> String?.enumOrDefault(default: T): T =
    this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

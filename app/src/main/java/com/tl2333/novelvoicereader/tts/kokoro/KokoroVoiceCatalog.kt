package com.tl2333.novelvoicereader.tts.kokoro

import com.tl2333.novelvoicereader.narration.SpeechLanguage

enum class VoiceGender { FEMALE, MALE }

data class KokoroVoice(
    val sid: Int,
    val internalName: String,
    val displayName: String,
    val gender: VoiceGender,
    val supportedLanguages: Set<SpeechLanguage>,
)

object KokoroVoiceCatalog {
    const val DEFAULT_FEMALE_SID = 3
    const val DEFAULT_MALE_SID = 58
    const val DEFAULT_FEMALE_NAME = "zf_001"
    const val DEFAULT_MALE_NAME = "zm_009"

    private val femaleNames = listOf(
        "zf_001", "zf_002", "zf_003", "zf_004", "zf_005", "zf_006", "zf_007", "zf_008",
        "zf_017", "zf_018", "zf_019", "zf_021", "zf_022", "zf_023", "zf_024", "zf_026",
        "zf_027", "zf_028", "zf_032", "zf_036", "zf_038", "zf_039", "zf_040", "zf_042",
        "zf_043", "zf_044", "zf_046", "zf_047", "zf_048", "zf_049", "zf_051", "zf_059",
        "zf_060", "zf_067", "zf_070", "zf_071", "zf_072", "zf_073", "zf_074", "zf_075",
        "zf_076", "zf_077", "zf_078", "zf_079", "zf_083", "zf_084", "zf_085", "zf_086",
        "zf_087", "zf_088", "zf_090", "zf_092", "zf_093", "zf_094", "zf_099",
    )
    private val maleNames = listOf(
        "zm_009", "zm_010", "zm_011", "zm_012", "zm_013", "zm_014", "zm_015", "zm_016",
        "zm_020", "zm_025", "zm_029", "zm_030", "zm_031", "zm_033", "zm_034", "zm_035",
        "zm_037", "zm_041", "zm_045", "zm_050", "zm_052", "zm_053", "zm_054", "zm_055",
        "zm_056", "zm_057", "zm_058", "zm_061", "zm_062", "zm_063", "zm_064", "zm_065",
        "zm_066", "zm_068", "zm_069", "zm_080", "zm_081", "zm_082", "zm_089", "zm_091",
        "zm_095", "zm_096", "zm_097", "zm_098", "zm_100",
    )

    val voices: List<KokoroVoice> = buildList {
        femaleNames.forEachIndexed { index, name -> add(voice(3 + index, name, VoiceGender.FEMALE)) }
        maleNames.forEachIndexed { index, name -> add(voice(58 + index, name, VoiceGender.MALE)) }
    }
    val femaleVoices: List<KokoroVoice> = voices.filter { it.gender == VoiceGender.FEMALE }
    val maleVoices: List<KokoroVoice> = voices.filter { it.gender == VoiceGender.MALE }
    val defaultFemale: KokoroVoice = requireBySid(DEFAULT_FEMALE_SID)
    val defaultMale: KokoroVoice = requireBySid(DEFAULT_MALE_SID)

    fun bySid(sid: Int): KokoroVoice? = voices.getOrNull(sid - DEFAULT_FEMALE_SID)?.takeIf { it.sid == sid }
    fun requireBySid(sid: Int): KokoroVoice = requireNotNull(bySid(sid)) { "Unknown Chinese Kokoro voice sid: $sid" }
    fun voicesFor(language: SpeechLanguage): List<KokoroVoice> = voices.filter { language in it.supportedLanguages }
    fun supports(language: SpeechLanguage): Boolean = voices.any { language in it.supportedLanguages }

    private fun voice(sid: Int, name: String, gender: VoiceGender): KokoroVoice {
        val number = name.substringAfter('_').toInt().toString().padStart(2, '0')
        val label = if (gender == VoiceGender.FEMALE) "\u4e2d\u6587\u5973\u58f0" else "\u4e2d\u6587\u7537\u58f0"
        return KokoroVoice(sid, name, "$label $number", gender, setOf(SpeechLanguage.ZH, SpeechLanguage.EN))
    }
}

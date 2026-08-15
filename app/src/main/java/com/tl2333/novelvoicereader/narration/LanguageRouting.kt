package com.tl2333.novelvoicereader.narration

import com.tl2333.novelvoicereader.tts.cache.CachedPcm16Audio

enum class SpeechLanguage(val tag: String) {
    ZH("zh-Hans"), EN("en-US"), JA("ja-JP");

    companion object {
        fun fromTag(tag: String?): SpeechLanguage? = when (tag.orEmpty().substringBefore('-').lowercase()) {
            "zh" -> ZH
            "en" -> EN
            "ja" -> JA
            else -> null
        }
    }
}

object LanguageDetector {
    fun detect(text: String, contextualLanguage: SpeechLanguage? = null): SpeechLanguage {
        val counts = scriptCounts(text)
        return when {
            counts.kana > 0 -> SpeechLanguage.JA
            counts.han > 0 && counts.latin == 0 -> contextualLanguage?.takeIf { it == SpeechLanguage.JA } ?: SpeechLanguage.ZH
            counts.han > 0 && counts.han >= counts.latin -> SpeechLanguage.ZH
            counts.latin > 0 -> SpeechLanguage.EN
            else -> contextualLanguage ?: SpeechLanguage.ZH
        }
    }

    fun detectAll(texts: List<String>): List<SpeechLanguage> {
        val explicit = texts.map { text ->
            val counts = scriptCounts(text)
            when {
                counts.kana > 0 -> SpeechLanguage.JA
                counts.han > 0 && counts.latin == 0 -> null
                counts.han > 0 && counts.han >= counts.latin -> SpeechLanguage.ZH
                counts.latin > 0 -> SpeechLanguage.EN
                else -> null
            }
        }
        return texts.indices.map { index ->
            explicit[index] ?: run {
                val previous = (index - 1 downTo 0).firstNotNullOfOrNull { explicit[it] }
                val next = (index + 1 until texts.size).firstNotNullOfOrNull { explicit[it] }
                val context = when {
                    previous == next -> previous
                    previous == SpeechLanguage.JA || next == SpeechLanguage.JA -> SpeechLanguage.JA
                    else -> previous ?: next
                }
                detect(texts[index], context)
            }
        }
    }

    private fun scriptCounts(text: String): ScriptCounts {
        var han = 0
        var kana = 0
        var latin = 0
        text.codePoints().forEach { codePoint ->
            when {
                codePoint in 0x3040..0x30ff || codePoint in 0x31f0..0x31ff -> kana++
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN -> latin++
            }
        }
        return ScriptCounts(han, kana, latin)
    }

    private data class ScriptCounts(val han: Int, val kana: Int, val latin: Int)
}

object EnglishTextNormalizer {
    const val VERSION = "english-v1"

    fun normalize(text: String): String = text
        .replace('\u0000', ' ')
        .replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), "")
        .replace(Regex("[ \\t]+"), " ")
        .trim()
}

interface JapaneseTtsBackend {
    val isAvailable: Boolean
    fun synthesize(segment: SpeechSegment): CachedPcm16Audio
}

class JapaneseNarrationUnsupportedException(message: String) : IllegalStateException(message)

object UnsupportedJapaneseTtsBackend : JapaneseTtsBackend {
    override val isAvailable: Boolean = false

    override fun synthesize(segment: SpeechSegment): CachedPcm16Audio {
        throw JapaneseNarrationUnsupportedException(
            "当前打包的 Kokoro v1.1-zh 只有中英文词典和中文声音，不可靠支持日语；请安装日语后端后重试。",
        )
    }
}

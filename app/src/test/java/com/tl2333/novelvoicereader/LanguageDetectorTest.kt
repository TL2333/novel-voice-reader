package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.narration.EnglishTextNormalizer
import com.tl2333.novelvoicereader.narration.LanguageDetector
import com.tl2333.novelvoicereader.narration.SpeechLanguage
import com.tl2333.novelvoicereader.tts.kokoro.KokoroVoiceCatalog
import com.tl2333.novelvoicereader.tts.tokenizer.EnglishSentenceTokenizer
import org.junit.Test

class LanguageDetectorTest {
    @Test
    fun detectsChineseEnglishAndJapaneseScripts() {
        assertThat(LanguageDetector.detect("夜色渐渐沉了下来。")) .isEqualTo(SpeechLanguage.ZH)
        assertThat(LanguageDetector.detect("The iPhone uses AI and GPT URLs.")) .isEqualTo(SpeechLanguage.EN)
        assertThat(LanguageDetector.detect("今日は静かな一日です。")) .isEqualTo(SpeechLanguage.JA)
    }

    @Test
    fun usesJapaneseContextForKanjiOnlySegment() {
        assertThat(LanguageDetector.detectAll(listOf("これは案内です。", "東京", "次の駅です。")))
            .containsExactly(SpeechLanguage.JA, SpeechLanguage.JA, SpeechLanguage.JA).inOrder()
        assertThat(LanguageDetector.detect("东京欢迎你。")) .isEqualTo(SpeechLanguage.ZH)
    }

    @Test
    fun englishNormalizerPreservesTechnicalTokensAndCapabilityIsHonest() {
        val text = "  iPhone   AI GPT https://example.test/a.b  "
        assertThat(EnglishTextNormalizer.normalize(text)).isEqualTo("iPhone AI GPT https://example.test/a.b")
        assertThat(EnglishSentenceTokenizer(maxCharacters = 24).tokenize("iPhone AI GPT https://example.test/a.b works.")
            .joinToString("") { it.text })
            .contains("https://example.test/a.b")
        assertThat(KokoroVoiceCatalog.supports(SpeechLanguage.ZH)).isTrue()
        assertThat(KokoroVoiceCatalog.supports(SpeechLanguage.EN)).isTrue()
        assertThat(KokoroVoiceCatalog.supports(SpeechLanguage.JA)).isFalse()
    }
}

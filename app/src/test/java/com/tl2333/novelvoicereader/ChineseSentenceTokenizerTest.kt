package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.tts.tokenizer.ChineseSentenceTokenizer
import org.junit.Test

class ChineseSentenceTokenizerTest {
    @Test
    fun splitsChineseSentencesAndPreservesOriginalOffsets() {
        val source = "夜色沉了。她问：“你来吗？”太好了！"
        val segments = ChineseSentenceTokenizer().tokenize(source)

        assertThat(segments.map { it.text }).containsExactly(
            "夜色沉了。",
            "她问：“你来吗？”",
            "太好了！",
        ).inOrder()
        segments.forEach { segment ->
            assertThat(source.substring(segment.startOffset, segment.endOffsetExclusive)).isEqualTo(segment.text)
        }
    }

    @Test
    fun doesNotSplitDecimalAndBoundsLongSentences() {
        val decimal = ChineseSentenceTokenizer().tokenize("温度是3.14度。测试结束。")
        assertThat(decimal).hasSize(2)

        val long = "这是很长的一段文字，需要在逗号处分开，以免单次送入模型的文本过长，并保留每一段在原文里的偏移。"
        val bounded = ChineseSentenceTokenizer(maxCharacters = 20).tokenize(long)
        assertThat(bounded.size).isGreaterThan(1)
        assertThat(bounded.all { it.text.length <= 20 }).isTrue()
    }

    @Test
    fun doesNotSplitEnglishWordsOrNumbersAtHardBoundary() {
        val source = "1234567890 abcdefghijklmnop 9876543210"

        val segments = ChineseSentenceTokenizer(maxCharacters = 20).tokenize(source)

        assertThat(segments.map { it.text }).containsExactly(
            "1234567890",
            "abcdefghijklmnop",
            "9876543210",
        ).inOrder()
        assertThat(segments.all { it.text.length <= 20 }).isTrue()
    }

    @Test
    fun preservesOneOversizedAsciiTokenForNativeGuardToReject() {
        val oversizedToken = "A".repeat(25)
        val source = "prefix $oversizedToken suffix"

        val segments = ChineseSentenceTokenizer(maxCharacters = 20).tokenize(source)

        assertThat(segments.map { it.text }).contains(oversizedToken)
        assertThat(segments.none { it.text != oversizedToken && it.text in oversizedToken }).isTrue()
    }
}

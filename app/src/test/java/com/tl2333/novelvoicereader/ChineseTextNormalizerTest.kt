package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.tts.tokenizer.ChineseTextNormalizer
import org.junit.Test

class ChineseTextNormalizerTest {
    @Test
    fun normalizesDatesTimesMoneyPercentAndWhitespace() {
        val result = ChineseTextNormalizer.normalize(
            "  2026-08-03  12:30，价格￥123.5，完成87.5%！  ",
        )

        assertThat(result).isEqualTo(
            "二零二六年八月三日 十二点三十分，价格一百二十三点五元，完成百分之八十七点五！",
        )
    }

    @Test
    fun decodesEntitiesAndNormalizesChapterNumberWithoutChangingWords() {
        val result = ChineseTextNormalizer.normalize("※ 第 12 章&nbsp;Tom &amp; 小雨……")

        assertThat(result).isEqualTo("第十二章 Tom & 小雨……")
    }
}

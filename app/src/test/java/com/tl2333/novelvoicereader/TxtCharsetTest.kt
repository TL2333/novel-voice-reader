package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.content.txt.TxtCharsetDetector
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import org.junit.Test

class TxtCharsetTest {
    @Test
    fun detectsUtfBomVariantsAndGb18030() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "正文".toByteArray()
        assertThat(TxtCharsetDetector.detect(ByteArrayInputStream(utf8Bom)).bomBytes).isEqualTo(3)
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "正文".toByteArray(Charsets.UTF_16LE)
        assertThat(TxtCharsetDetector.detect(ByteArrayInputStream(utf16)).charset).isEqualTo(Charsets.UTF_16LE)
        val gb = "第一章\n这是中文正文，包含常用标点。".toByteArray(Charset.forName("GB18030"))
        assertThat(TxtCharsetDetector.detect(ByteArrayInputStream(gb)).charset.name()).isAnyOf("GB18030", "GBK")
    }
}

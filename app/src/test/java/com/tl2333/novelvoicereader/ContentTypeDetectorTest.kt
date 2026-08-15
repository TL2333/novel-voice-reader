package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.content.web.ContentTypeDetector
import com.tl2333.novelvoicereader.content.web.WebContentKind
import org.junit.Test

class ContentTypeDetectorTest {
    @Test
    fun usesMagicBytesHeadersAndExtension() {
        assertThat(ContentTypeDetector.detect("text/html; charset=utf-8", "<html></html>".toByteArray())).isEqualTo(WebContentKind.HTML)
        assertThat(ContentTypeDetector.detect("application/octet-stream", "%PDF-1.7".toByteArray())).isEqualTo(WebContentKind.PDF)
        assertThat(ContentTypeDetector.detect("text/plain", "正文".toByteArray())).isEqualTo(WebContentKind.TXT)
        assertThat(ContentTypeDetector.detect(null, byteArrayOf(0x50, 0x4b, 0x03, 0x04), "/book.docx")).isEqualTo(WebContentKind.UNSUPPORTED)
    }
}

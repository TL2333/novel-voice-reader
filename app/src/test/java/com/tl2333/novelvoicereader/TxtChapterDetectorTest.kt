package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.content.txt.TxtChapterDetector
import org.junit.Test

class TxtChapterDetectorTest {
    @Test
    fun requiresStructuralEvidenceAroundChapterPattern() {
        assertThat(TxtChapterDetector.isChapter("第一百章 风雨", previousBlank = true, nextBlank = true)).isTrue()
        assertThat(TxtChapterDetector.isChapter("Chapter 12", previousBlank = true, nextBlank = true)).isTrue()
        assertThat(TxtChapterDetector.isChapter("他在第一章提到这件事", previousBlank = true, nextBlank = true)).isFalse()
        assertThat(TxtChapterDetector.isChapter("第一章", previousBlank = false, nextBlank = false)).isFalse()
    }
}

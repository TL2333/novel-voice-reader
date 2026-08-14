package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.content.model.DocumentBounds
import com.tl2333.novelvoicereader.content.pdf.PdfReadingOrderResolver
import com.tl2333.novelvoicereader.content.pdf.PdfTextFragment
import org.junit.Test

class PdfReadingOrderTest {
    private val resolver = PdfReadingOrderResolver()

    @Test
    fun ordersTwoColumnsAndRepairsAsciiHyphenation() {
        val fragments = listOf(
            fragment(1, "Right second", 330f, 100f, 560f, 130f),
            fragment(1, "national result", 60f, 140f, 280f, 170f),
            fragment(1, "inter-", 60f, 100f, 280f, 130f),
            fragment(1, "Right first", 330f, 60f, 560f, 90f),
        )

        assertThat(resolver.resolve(fragments).map { it.text }).containsExactly(
            "international result",
            "Right first",
            "Right second",
        ).inOrder()
    }

    @Test
    fun removesRepeatedHeadersFootersAndPageNumbersAcrossPages() {
        val fragments = (1..4).flatMap { page ->
            listOf(
                fragment(page, "Quarterly Report", 50f, 10f, 550f, 35f),
                fragment(page, "正文第 $page 页", 50f, 120f, 550f, 170f),
                fragment(page, page.toString(), 280f, 760f, 320f, 785f),
            )
        }

        val text = resolver.resolve(fragments).map { it.text }
        assertThat(text).containsExactly("正文第 1 页", "正文第 2 页", "正文第 3 页", "正文第 4 页").inOrder()
    }

    private fun fragment(page: Int, text: String, left: Float, top: Float, right: Float, bottom: Float) =
        PdfTextFragment(page, text, DocumentBounds(left, top, right, bottom), 600f, 800f)
}

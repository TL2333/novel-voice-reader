package com.tl2333.novelvoicereader.content.pdf

import com.tl2333.novelvoicereader.content.model.BlockType
import com.tl2333.novelvoicereader.content.model.DocumentBounds

/** Restores a useful reading order from positioned PDF text without flattening a page to page.text. */
class PdfReadingOrderResolver {
    fun resolve(fragments: List<PdfTextFragment>): List<PdfResolvedBlock> {
        val usable = fragments.filter { it.text.isNotBlank() }
        val repeatedMargins = repeatedMarginKeys(usable)
        return usable.groupBy(PdfTextFragment::page).toSortedMap().flatMap { (_, pageFragments) ->
            val filtered = pageFragments.filterNot { fragment ->
                isMargin(fragment) && normalizeRepeatedText(fragment.text) in repeatedMargins
            }
            val ordered = orderPage(filtered)
            mergeHyphenated(ordered).map { fragment ->
                PdfResolvedBlock(
                    page = fragment.page,
                    text = normalizeWhitespace(fragment.text),
                    bounds = fragment.bounds,
                    type = classify(fragment.text),
                    confidence = fragment.confidence,
                    source = fragment.source,
                )
            }.filter { it.text.isNotBlank() }
        }
    }

    private fun repeatedMarginKeys(fragments: List<PdfTextFragment>): Set<String> {
        val pages = fragments.map(PdfTextFragment::page).distinct().size
        if (pages < 3) return emptySet()
        val minimumOccurrences = maxOf(3, (pages * 0.6f).toInt())
        return fragments.asSequence()
            .filter(::isMargin)
            .map { it.page to normalizeRepeatedText(it.text) }
            .filter { it.second.isNotBlank() }
            .distinct()
            .groupingBy { it.second }
            .eachCount()
            .filterValues { it >= minimumOccurrences }
            .keys
    }

    private fun orderPage(fragments: List<PdfTextFragment>): List<PdfTextFragment> {
        if (fragments.size < 4 || fragments.any { it.bounds == null }) return fragments.sortedWith(POSITION_ORDER)
        val width = fragments.first().pageWidth
        val spanning = fragments.filter { it.bounds!!.left < width * 0.35f && it.bounds.right > width * 0.65f }
        val left = fragments.filter { it !in spanning && it.bounds!!.centerX() < width * 0.5f }
        val right = fragments.filter { it !in spanning && it.bounds!!.centerX() >= width * 0.5f }
        val isTwoColumn = left.size >= 2 && right.size >= 2 &&
            left.maxOf { it.bounds!!.right } <= right.minOf { it.bounds!!.left } + width * 0.04f
        if (!isTwoColumn) return fragments.sortedWith(POSITION_ORDER)

        val topSpanningBottom = spanning.minOfOrNull { it.bounds!!.bottom } ?: 0f
        val topSpanning = spanning.filter { it.bounds!!.top <= topSpanningBottom }.sortedWith(POSITION_ORDER)
        val bottomSpanning = spanning.filterNot { it in topSpanning }.sortedWith(POSITION_ORDER)
        return topSpanning + left.sortedWith(POSITION_ORDER) + right.sortedWith(POSITION_ORDER) + bottomSpanning
    }

    private fun mergeHyphenated(ordered: List<PdfTextFragment>): List<PdfTextFragment> {
        val output = mutableListOf<PdfTextFragment>()
        ordered.forEach { current ->
            val previous = output.lastOrNull()
            if (
                previous != null && previous.page == current.page &&
                previous.text.trimEnd().endsWith('-') && current.text.trimStart().firstOrNull()?.isLowerCase() == true
            ) {
                output[output.lastIndex] = previous.copy(
                    text = previous.text.trimEnd().dropLast(1) + current.text.trimStart(),
                    bounds = union(previous.bounds, current.bounds),
                    confidence = listOfNotNull(previous.confidence, current.confidence).minOrNull(),
                )
            } else {
                output += current
            }
        }
        return output
    }

    private fun isMargin(fragment: PdfTextFragment): Boolean {
        val bounds = fragment.bounds ?: return false
        return bounds.bottom <= fragment.pageHeight * 0.13f || bounds.top >= fragment.pageHeight * 0.87f
    }

    private fun classify(text: String): BlockType {
        val normalized = normalizeWhitespace(text)
        return when {
            normalized.length <= 60 && HEADING.matches(normalized) -> BlockType.HEADING
            normalized.length <= 48 && normalized.none { it in "。！？.!?;；" } -> BlockType.HEADING
            normalized.count { it == '\t' || it == '|' } >= 2 -> BlockType.TABLE_TEXT
            else -> BlockType.PARAGRAPH
        }
    }

    private fun normalizeRepeatedText(text: String): String = normalizeWhitespace(text)
        .lowercase()
        .replace(Regex("[\\p{N}ivxlcdm一二三四五六七八九十百]+"), "#")
        .replace(Regex("[^\\p{L}#]+"), "")

    private fun normalizeWhitespace(text: String): String = text
        .replace(Regex("[\\t\\r\\n ]+"), " ")
        .trim()

    private fun union(first: DocumentBounds?, second: DocumentBounds?): DocumentBounds? = when {
        first == null -> second
        second == null -> first
        else -> DocumentBounds(
            left = minOf(first.left, second.left),
            top = minOf(first.top, second.top),
            right = maxOf(first.right, second.right),
            bottom = maxOf(first.bottom, second.bottom),
        )
    }

    private fun DocumentBounds.centerX(): Float = (left + right) / 2f

    companion object {
        private val HEADING = Regex("^(第.{1,12}[章节篇卷部]|chapter\\s+\\d+|abstract|摘要|引言|结论).*$", RegexOption.IGNORE_CASE)
        private val POSITION_ORDER = compareBy<PdfTextFragment>({ it.bounds?.top ?: Float.MAX_VALUE }, { it.bounds?.left ?: Float.MAX_VALUE })
    }
}

package com.tl2333.novelvoicereader.tts.tokenizer

data class SentenceSegment(
    val index: Int,
    val text: String,
    val startOffset: Int,
    val endOffsetExclusive: Int,
)

/** Pure domain segmentation; offsets are relative to the supplied text and carry no Navigator state. */
class ChineseSentenceTokenizer(private val maxCharacters: Int = 120) {
    init {
        require(maxCharacters >= 16)
    }

    fun tokenize(text: String): List<SentenceSegment> {
        if (text.isBlank()) return emptyList()
        val rough = mutableListOf<IntRange>()
        var start = 0
        var index = 0
        while (index < text.length) {
            val char = text[index]
            val end = when {
                char in TERMINATORS -> includeClosingQuotes(text, index + 1)
                char == '.' && isSentencePeriod(text, index) -> includeClosingQuotes(text, index + 1)
                char == '\u2026' && index + 1 < text.length && text[index + 1] == '\u2026' ->
                    includeClosingQuotes(text, index + 2)
                char == '\u2014' && index + 1 < text.length && text[index + 1] == '\u2014' ->
                    includeClosingQuotes(text, index + 2)
                else -> -1
            }
            if (end >= 0) {
                addTrimmedRange(text, start, end, rough)
                start = end
                index = end
            } else {
                index++
            }
        }
        addTrimmedRange(text, start, text.length, rough)

        val bounded = rough.flatMap { splitLong(text, it.first, it.last + 1) }.toMutableList()
        mergeVeryShort(text, bounded)
        return bounded.mapIndexed { segmentIndex, range ->
            SentenceSegment(segmentIndex, text.substring(range), range.first, range.last + 1)
        }
    }

    private fun splitLong(text: String, start: Int, end: Int): List<IntRange> {
        val output = mutableListOf<IntRange>()
        var cursor = start
        while (end - cursor > maxCharacters) {
            val hardEnd = cursor + maxCharacters
            var split = -1
            for (candidate in hardEnd downTo cursor + maxCharacters / 2) {
                if (
                    (text[candidate - 1] in SOFT_BREAKS || text[candidate - 1].isWhitespace()) &&
                    !isInsideAsciiToken(text, candidate)
                ) {
                    split = candidate
                    break
                }
            }
            if (split < 0) {
                split = (hardEnd downTo cursor + 1)
                    .firstOrNull { candidate -> !isInsideAsciiToken(text, candidate) }
                    ?: findAsciiTokenEnd(text, hardEnd, end)
            }
            addTrimmedRange(text, cursor, split, output)
            cursor = split
        }
        addTrimmedRange(text, cursor, end, output)
        return output
    }

    /** A boundary in an ASCII word/number is unsafe, even when it lands on the hard limit. */
    private fun isInsideAsciiToken(text: String, boundary: Int): Boolean =
        boundary > 0 && boundary < text.length &&
            text[boundary - 1].isAsciiTokenCharacter() && text[boundary].isAsciiTokenCharacter()

    /**
     * A single token can itself exceed the configured bound. Preserve it rather than corrupting the
     * word/number; the native-call guard rejects that oversized segment before invoking Kokoro.
     */
    private fun findAsciiTokenEnd(text: String, initialBoundary: Int, end: Int): Int {
        var boundary = initialBoundary
        while (boundary < end && isInsideAsciiToken(text, boundary)) boundary++
        return boundary
    }

    private fun Char.isAsciiTokenCharacter(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' ||
            this == '_' || this == '\'' || this == '-' || this == '.'

    private fun mergeVeryShort(text: String, ranges: MutableList<IntRange>) {
        var index = 0
        while (index < ranges.size) {
            val current = ranges[index]
            val meaningful = text.substring(current).count { !it.isWhitespace() && it !in PUNCTUATION }
            if (meaningful <= 1 && ranges.size > 1) {
                if (index + 1 < ranges.size && ranges[index + 1].last - current.first + 1 <= maxCharacters) {
                    ranges[index] = current.first..ranges[index + 1].last
                    ranges.removeAt(index + 1)
                } else if (index > 0 && current.last - ranges[index - 1].first + 1 <= maxCharacters) {
                    ranges[index - 1] = ranges[index - 1].first..current.last
                    ranges.removeAt(index)
                    index--
                } else {
                    index++
                }
            } else {
                index++
            }
        }
    }

    private fun includeClosingQuotes(text: String, initialEnd: Int): Int {
        var end = initialEnd
        while (end < text.length && text[end] in CLOSING_QUOTES) end++
        return end
    }

    private fun isSentencePeriod(text: String, index: Int): Boolean {
        val beforeDigit = index > 0 && text[index - 1].isDigit()
        val afterDigit = index + 1 < text.length && text[index + 1].isDigit()
        if (beforeDigit && afterDigit) return false
        return index + 1 == text.length || text[index + 1].isWhitespace() || text[index + 1] in CLOSING_QUOTES
    }

    private fun addTrimmedRange(text: String, rawStart: Int, rawEnd: Int, output: MutableList<IntRange>) {
        var start = rawStart
        var end = rawEnd
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start < end) output += start until end
    }

    private companion object {
        val TERMINATORS = setOf('\u3002', '\uff01', '\uff1f', '!', '?', '\uff1b', ';')
        val SOFT_BREAKS = setOf('\uff0c', ',', '\u3001', '\uff1a', ':', '\uff1b', ';', '\u2014')
        val CLOSING_QUOTES = setOf('\u201d', '\u2019', '\u300d', '\u300f', '\u300b', '\u3011', ')', ']', '}')
        val PUNCTUATION = TERMINATORS + SOFT_BREAKS + CLOSING_QUOTES + setOf('\u201c', '\u2018', '\u300c', '\u300e')
    }
}

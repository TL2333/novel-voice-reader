@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.tl2333.novelvoicereader.reader

import com.tl2333.novelvoicereader.tts.tokenizer.ChineseSentenceTokenizer
import org.readium.r2.shared.util.tokenizer.TextTokenizer

/**
 * Adapts the app's Chinese sentence splitter to Readium's source-range tokenizer contract.
 *
 * The returned ranges always address the untouched EPUB string. Text normalization deliberately
 * happens later in Kokoro's `speak()` path; normalizing here would invalidate Readium Locators and
 * sentence decorations whenever a number or date changes length.
 */
class ChineseReadiumTextTokenizer(
    private val delegate: ChineseSentenceTokenizer = ChineseSentenceTokenizer(maxCharacters = 80),
) : TextTokenizer {
    override fun tokenize(data: String): List<IntRange> = delegate.tokenize(data)
        .asSequence()
        .filter { segment -> segment.text.any(Char::isLetterOrDigit) }
        .mapNotNull { segment ->
            val start = segment.startOffset.coerceIn(0, data.length)
            val endExclusive = segment.endOffsetExclusive.coerceIn(start, data.length)
            (start until endExclusive).takeUnless(IntRange::isEmpty)
        }
        .toList()
}

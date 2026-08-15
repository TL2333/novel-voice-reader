package com.tl2333.novelvoicereader.tts.tokenizer

/**
 * English-facing sentence adapter. It keeps ASCII technical tokens intact and intentionally has
 * no dependency on Chinese text normalization.
 */
class EnglishSentenceTokenizer(maxCharacters: Int = 120) {
    private val boundaryTokenizer = ChineseSentenceTokenizer(maxCharacters)

    fun tokenize(text: String): List<SentenceSegment> = boundaryTokenizer.tokenize(text)
}

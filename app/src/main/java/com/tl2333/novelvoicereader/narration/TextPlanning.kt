package com.tl2333.novelvoicereader.narration

import com.tl2333.novelvoicereader.content.model.BlockType
import com.tl2333.novelvoicereader.content.model.CanonicalDocument
import com.tl2333.novelvoicereader.filesystem.Sha256
import com.tl2333.novelvoicereader.tts.tokenizer.ChineseSentenceTokenizer
import com.tl2333.novelvoicereader.tts.tokenizer.ChineseTextNormalizer
import com.tl2333.novelvoicereader.tts.tokenizer.EnglishSentenceTokenizer

data class NarrationProfile(
    val semicolonPauseMs: Long = 130,
    val periodPauseMs: Long = 200,
    val questionPauseMs: Long = 220,
    val exclamationPauseMs: Long = 190,
    val paragraphPauseMs: Long = 320,
    val headingPauseMs: Long = 550,
) {
    init {
        require(listOf(semicolonPauseMs, periodPauseMs, questionPauseMs, exclamationPauseMs, paragraphPauseMs, headingPauseMs).all { it >= 0 })
    }
}

object TextCleaner {
    fun clean(text: String): String = text
        .replace('\u0000', ' ')
        .replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), "")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .trim()
}

object PausePlanner {
    fun pauseAfter(text: String, blockType: BlockType, profile: NarrationProfile): Long = when {
        blockType == BlockType.TITLE || blockType == BlockType.HEADING -> profile.headingPauseMs
        text.endsWith('？') || text.endsWith('?') -> profile.questionPauseMs
        text.endsWith('！') || text.endsWith('!') -> profile.exclamationPauseMs
        text.endsWith('；') || text.endsWith(';') -> profile.semicolonPauseMs
        text.endsWith('。') || text.endsWith('.') -> profile.periodPauseMs
        else -> profile.paragraphPauseMs
    }
}

class SpeechPlanner(
    private val tokenizer: ChineseSentenceTokenizer = ChineseSentenceTokenizer(maxCharacters = 160),
    private val englishTokenizer: EnglishSentenceTokenizer = EnglishSentenceTokenizer(maxCharacters = 160),
    private val profile: NarrationProfile = NarrationProfile(),
) {
    fun plan(document: CanonicalDocument): List<SpeechSegment> {
        val candidates = document.blocks.flatMap { block ->
            val cleaned = TextCleaner.clean(block.text)
            if (cleaned.isBlank() || block.type == BlockType.PAGE_BREAK) return@flatMap emptyList()
            val tokens = if (LanguageDetector.detect(cleaned) == SpeechLanguage.EN) {
                englishTokenizer.tokenize(cleaned)
            } else {
                tokenizer.tokenize(cleaned)
            }
            tokens.map { token ->
                Candidate(block.id, block.sectionId, block.type, block.anchor, token.startOffset, token.endOffsetExclusive, token.text)
            }
        }
        val languages = LanguageDetector.detectAll(candidates.map(Candidate::text))
        return candidates.mapIndexed { order, candidate ->
            val language = languages[order]
            val normalized = when (language) {
                SpeechLanguage.ZH -> ChineseTextNormalizer.normalize(candidate.text)
                SpeechLanguage.EN -> EnglishTextNormalizer.normalize(candidate.text)
                SpeechLanguage.JA -> TextCleaner.clean(candidate.text)
            }
            SpeechSegment(
                id = Sha256.hash("${document.id}\u0000${candidate.blockId}\u0000${candidate.start}\u0000${language.tag}\u0000$normalized"),
                documentId = document.id,
                sectionId = candidate.sectionId,
                blockId = candidate.blockId,
                order = order,
                text = normalized,
                anchor = candidate.anchor.copy(charStart = candidate.start, charEnd = candidate.end),
                plannedPauseMs = PausePlanner.pauseAfter(normalized, candidate.blockType, profile),
                language = language,
            )
        }
    }

    private data class Candidate(
        val blockId: String,
        val sectionId: String,
        val blockType: BlockType,
        val anchor: com.tl2333.novelvoicereader.content.model.DocumentLocation,
        val start: Int,
        val end: Int,
        val text: String,
    )
}

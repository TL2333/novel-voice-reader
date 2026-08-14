package com.tl2333.novelvoicereader.narration

import com.tl2333.novelvoicereader.content.model.BlockType
import com.tl2333.novelvoicereader.content.model.CanonicalDocument
import com.tl2333.novelvoicereader.filesystem.Sha256
import com.tl2333.novelvoicereader.tts.tokenizer.ChineseSentenceTokenizer
import com.tl2333.novelvoicereader.tts.tokenizer.ChineseTextNormalizer

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
    private val profile: NarrationProfile = NarrationProfile(),
) {
    fun plan(document: CanonicalDocument): List<SpeechSegment> {
        var order = 0
        return document.blocks.flatMap { block ->
            val cleaned = TextCleaner.clean(block.text)
            if (cleaned.isBlank() || block.type == BlockType.PAGE_BREAK) return@flatMap emptyList()
            tokenizer.tokenize(cleaned).map { token ->
                val normalized = ChineseTextNormalizer.normalize(token.text)
                SpeechSegment(
                    id = Sha256.hash("${document.id}\u0000${block.id}\u0000${token.startOffset}\u0000$normalized"),
                    documentId = document.id,
                    sectionId = block.sectionId,
                    blockId = block.id,
                    order = order++,
                    text = normalized,
                    anchor = block.anchor.copy(charStart = token.startOffset, charEnd = token.endOffsetExclusive),
                    plannedPauseMs = PausePlanner.pauseAfter(normalized, block.type, profile),
                )
            }
        }
    }
}

package com.tl2333.novelvoicereader.tts.cache

import com.tl2333.novelvoicereader.filesystem.Sha256
import com.tl2333.novelvoicereader.tts.tokenizer.NarrationStyle

data class TtsCacheKeyInput(
    val kokoroModelCommit: String,
    val sherpaVersion: String,
    val normalizedText: String,
    val voiceSid: Int,
    val style: NarrationStyle,
    val tokenizerVersion: String,
)

object TtsCacheKey {
    fun create(input: TtsCacheKeyInput): String {
        require(input.kokoroModelCommit.isNotBlank())
        require(input.sherpaVersion.isNotBlank())
        require(input.normalizedText.isNotBlank())
        require(input.voiceSid >= 0)
        require(input.tokenizerVersion.isNotBlank())
        val canonical = listOf(
            input.kokoroModelCommit.trim(),
            input.sherpaVersion.trim(),
            input.normalizedText,
            input.voiceSid.toString(),
            input.style.name.lowercase(),
            input.tokenizerVersion.trim(),
        ).joinToString(separator = "\u0000")
        return Sha256.hash(canonical)
    }

    fun utteranceHash(normalizedText: String): String = Sha256.hash(normalizedText)
}

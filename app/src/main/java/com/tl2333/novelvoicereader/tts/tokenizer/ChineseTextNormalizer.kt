package com.tl2333.novelvoicereader.tts.tokenizer

import java.math.BigInteger

/** Normalizes only the copy sent to TTS; callers retain the EPUB display text unchanged. */
object ChineseTextNormalizer {
    private val datePattern = Regex("(?<!\\d)(\\d{4})(?:年|[-/.])(\\d{1,2})(?:月|[-/.])(\\d{1,2})日?(?!\\d)")
    private val timePattern = Regex("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)")
    private val currencyPattern = Regex("([￥¥$])\\s*([+-]?\\d+(?:\\.\\d+)?)")
    private val percentPattern = Regex("([+-]?\\d+(?:\\.\\d+)?)\\s*[%％]")
    private val chapterPattern = Regex("第\\s*(\\d+)\\s*([章节回卷部篇])")
    private val numberPattern = Regex("[+-]?\\d+(?:\\.\\d+)?")
    private val numericEntity = Regex("&#(x[0-9a-fA-F]+|\\d+);")

    fun normalize(source: String): String {
        var text = decodeEntities(source)
            .replace('\u00a0', ' ')
            .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
            .replace(Regex("[★☆◆◇■□●○※]+"), " ")
            .replace("...", "……")
            .replace(Regex("…{2,}"), "……")
            .replace(Regex("([！？!?。])\\1+"), "\$1")

        text = datePattern.replace(text) { match ->
            val year = match.groupValues[1].map(::spokenDigit).joinToString("")
            "$year\u5e74${spokenInteger(match.groupValues[2])}\u6708${spokenInteger(match.groupValues[3])}\u65e5"
        }
        text = timePattern.replace(text) { match ->
            val hour = spokenInteger(match.groupValues[1])
            val minutes = match.groupValues[2].toInt()
            if (minutes == 0) "$hour\u70b9" else "$hour\u70b9${spokenInteger(minutes.toString())}\u5206"
        }
        text = currencyPattern.replace(text) { match ->
            val unit = if (match.groupValues[1] == "$") "\u7f8e\u5143" else "\u5143"
            spokenNumber(match.groupValues[2]) + unit
        }
        text = percentPattern.replace(text) { match -> "\u767e\u5206\u4e4b" + spokenNumber(match.groupValues[1]) }
        text = chapterPattern.replace(text) { match ->
            "\u7b2c${spokenInteger(match.groupValues[1])}${match.groupValues[2]}"
        }
        text = numberPattern.replace(text) { match -> spokenNumber(match.value) }
        return text
            .replace(Regex("[\\t\\r\\n ]+"), " ")
            .replace(Regex("\\s+([，。！？；：、])"), "\$1")
            .trim()
    }

    fun spokenNumber(raw: String): String {
        val sign = when {
            raw.startsWith('-') -> "\u8d1f"
            else -> ""
        }
        val unsigned = raw.removePrefix("-").removePrefix("+")
        val parts = unsigned.split('.', limit = 2)
        val integer = spokenInteger(parts[0])
        val decimal = parts.getOrNull(1)
            ?.takeIf(String::isNotEmpty)
            ?.map(::spokenDigit)
            ?.joinToString("")
            ?.let { "\u70b9$it" }
            .orEmpty()
        return sign + integer + decimal
    }

    fun spokenInteger(raw: String): String {
        val normalized = raw.trimStart('0').ifEmpty { "0" }
        if (normalized == "0") return "\u96f6"
        if (normalized.length > UNITS.size) return normalized.map(::spokenDigit).joinToString("")
        runCatching { BigInteger(normalized) }.getOrElse { return normalized }
        val output = StringBuilder()
        var pendingZero = false
        normalized.forEachIndexed { index, char ->
            val digit = char.digitToInt()
            val unitIndex = normalized.lastIndex - index
            if (digit == 0) {
                if (output.isNotEmpty() && normalized.drop(index + 1).any { it != '0' }) pendingZero = true
            } else {
                if (pendingZero) {
                    output.append('\u96f6')
                    pendingZero = false
                }
                output.append(DIGITS[digit]).append(UNITS[unitIndex])
            }
        }
        return output.toString().removePrefix("\u4e00\u5341")
            .let { if (normalized.length == 2 && normalized[0] == '1') "\u5341$it" else it }
            .replace("\u5341\u5341", "\u5341")
    }

    private fun decodeEntities(source: String): String {
        var text = source
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
        text = numericEntity.replace(text) { match ->
            val value = match.groupValues[1]
            val codePoint = if (value.startsWith('x', ignoreCase = true)) {
                value.drop(1).toIntOrNull(16)
            } else {
                value.toIntOrNull()
            }
            codePoint
                ?.takeIf { Character.isValidCodePoint(it) }
                ?.let { String(Character.toChars(it)) }
                ?: match.value
        }
        return text
    }

    private fun spokenDigit(char: Char): Char = DIGITS.getOrElse(char.digitToIntOrNull() ?: -1) { char }

    private const val DIGITS = "\u96f6\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d"
    private val UNITS = arrayOf("", "\u5341", "\u767e", "\u5343", "\u4e07", "\u5341\u4e07", "\u767e\u4e07", "\u5343\u4e07", "\u4ebf", "\u5341\u4ebf", "\u767e\u4ebf", "\u5343\u4ebf", "\u4e07\u4ebf")
}

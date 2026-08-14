package com.tl2333.novelvoicereader.content.txt

object TxtChapterDetector {
    private val heading = Regex(
        "^(?:第[0-9零〇一二三四五六七八九十百千万两]+[章节回卷部篇]|卷[0-9零〇一二三四五六七八九十百千万两]+|序章|楔子|终章|番外(?:[0-9零〇一二三四五六七八九十百千万两]*)|Chapter\\s+\\d+)(?:[ 　:：].*)?$",
        RegexOption.IGNORE_CASE,
    )

    fun score(line: String, previousBlank: Boolean, nextBlank: Boolean): Int {
        val value = line.trim()
        if (value.isEmpty() || value.length > 80 || !heading.matches(value)) return 0
        return 4 +
            (if (previousBlank) 2 else 0) +
            (if (nextBlank) 2 else 0) +
            (if (value.length <= 30) 1 else 0)
    }

    fun isChapter(line: String, previousBlank: Boolean, nextBlank: Boolean): Boolean =
        score(line, previousBlank, nextBlank) >= 6
}

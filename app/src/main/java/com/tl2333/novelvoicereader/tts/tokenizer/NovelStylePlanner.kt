package com.tl2333.novelvoicereader.tts.tokenizer

enum class NarrationStyle { NEUTRAL, HAPPY, SAD, ANGRY, EXCITED }

data class StyleParameters(
    val speed: Float,
    val gain: Float,
    val pauseMultiplier: Float,
)

data class StylePlan(
    val style: NarrationStyle,
    val parameters: StyleParameters,
    val confidence: Float,
    val automatic: Boolean,
)

object NovelStylePlanner {
    private val parameters = mapOf(
        NarrationStyle.NEUTRAL to StyleParameters(1.00f, 1.00f, 1.00f),
        NarrationStyle.HAPPY to StyleParameters(1.05f, 1.02f, 0.85f),
        NarrationStyle.SAD to StyleParameters(0.88f, 0.94f, 1.25f),
        NarrationStyle.ANGRY to StyleParameters(1.07f, 1.03f, 0.80f),
        NarrationStyle.EXCITED to StyleParameters(1.10f, 1.02f, 0.78f),
    )

    fun plan(
        current: String,
        previous: String? = null,
        next: String? = null,
        automatic: Boolean = true,
        forcedStyle: NarrationStyle? = null,
    ): StylePlan {
        if (forcedStyle != null) return result(forcedStyle, 1f, false)
        if (!automatic) return result(NarrationStyle.NEUTRAL, 1f, false)

        val context = listOfNotNull(previous, current, next).joinToString(" ")
        val scores = linkedMapOf(
            NarrationStyle.HAPPY to score(context, HAPPY_WORDS),
            NarrationStyle.SAD to score(context, SAD_WORDS),
            NarrationStyle.ANGRY to score(context, ANGRY_WORDS),
            NarrationStyle.EXCITED to score(context, EXCITED_WORDS),
        )
        if (current.count { it == '\uff01' || it == '!' } >= 2) scores[NarrationStyle.EXCITED] = scores.getValue(NarrationStyle.EXCITED) + 2
        if (current.contains("?!") || current.contains("\uff1f\uff01")) scores[NarrationStyle.EXCITED] = scores.getValue(NarrationStyle.EXCITED) + 1
        if (current.contains(Regex("[\u201c\u300c].*[\u201d\u300d]"))) {
            scores[NarrationStyle.ANGRY] = scores.getValue(NarrationStyle.ANGRY) + if (current.contains('\uff01')) 1 else 0
        }
        val maximum = scores.values.maxOrNull() ?: 0
        val winners = scores.filterValues { it == maximum }.keys
        if (maximum < 2 || winners.size != 1) return result(NarrationStyle.NEUTRAL, 0f, true)
        return result(winners.single(), (maximum / 5f).coerceIn(0.4f, 1f), true)
    }

    fun parameters(style: NarrationStyle): StyleParameters = parameters.getValue(style)

    private fun score(text: String, words: Set<String>): Int = words.sumOf { if (text.contains(it)) 2 else 0 }
    private fun result(style: NarrationStyle, confidence: Float, automatic: Boolean) =
        StylePlan(style, parameters(style), confidence, automatic)

    private val HAPPY_WORDS = setOf("\u5f00\u5fc3", "\u6b22\u559c", "\u5fae\u7b11", "\u7b11\u9053", "\u8f7b\u5feb", "\u5e78\u798f")
    private val SAD_WORDS = setOf("\u60b2\u4f24", "\u96be\u8fc7", "\u54ed", "\u773c\u6cea", "\u79bb\u522b", "\u7edd\u671b", "\u6c89\u9ed8")
    private val ANGRY_WORDS = setOf("\u6124\u6012", "\u6012", "\u4f4f\u53e3", "\u6df7\u86cb", "\u53ef\u6076", "\u543c", "\u54c6\u54ee")
    private val EXCITED_WORDS = setOf("\u6fc0\u52a8", "\u5174\u594b", "\u592a\u597d\u4e86", "\u6210\u529f\u4e86", "\u7ec8\u4e8e", "\u51b2")
}

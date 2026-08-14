package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import java.nio.charset.Charset
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class NarrationCorpusTest {
    @Test
    fun coversRequiredNarrationCategoriesAndGb18030Fixture() {
        val loader = requireNotNull(javaClass.classLoader)
        val categories = requireNotNull(loader.getResourceAsStream("narration-corpus/segments.jsonl"))
            .bufferedReader().useLines { lines ->
                lines.map { Json.parseToJsonElement(it).jsonObject.getValue("category").jsonPrimitive.content }.toSet()
            }
        assertThat(categories).containsAtLeast(
            "narration", "dialogue", "continuous_dialogue", "short", "long", "numbers", "date_time",
            "money_percent", "english", "mixed", "question", "exclamation", "ellipsis_dash", "heading", "extreme",
        )
        val encoded = requireNotNull(loader.getResourceAsStream("narration-corpus/sample-gb18030.base64"))
            .bufferedReader().readText().trim()
        val decoded = Base64.getDecoder().decode(encoded).toString(Charset.forName("GB18030"))
        assertThat(decoded).contains("GB18030")
        assertThat(decoded).contains("第一章")
    }
}

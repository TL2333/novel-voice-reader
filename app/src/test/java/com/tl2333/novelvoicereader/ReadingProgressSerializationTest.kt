package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.data.database.LocatorJson
import org.junit.Assert.assertThrows
import org.junit.Test

class ReadingProgressSerializationTest {
    @Test
    fun preservesCompleteReadiumLocatorIncludingUnknownFields() {
        val source = """{
            "href":"chapter-1.xhtml",
            "type":"application/xhtml+xml",
            "locations":{"progression":0.25,"totalProgression":0.42,"cssSelector":"#p3"},
            "text":{"highlight":"夜色渐渐沉了下来。"},
            "future":{"extension":true}
        }""".trimIndent()

        val stored = LocatorJson.canonicalize(source)
        val parsed = LocatorJson.parse(stored)

        assertThat(parsed["future"]).isNotNull()
        assertThat(LocatorJson.totalProgression(stored)!!).isWithin(0.0001).of(0.42)
        assertThat(parsed["href"].toString()).isEqualTo("\"chapter-1.xhtml\"")
    }

    @Test
    fun rejectsLocatorWithoutHref() {
        assertThrows(IllegalArgumentException::class.java) {
            LocatorJson.canonicalize("""{"locations":{"progression":0.2}}""")
        }
    }
}

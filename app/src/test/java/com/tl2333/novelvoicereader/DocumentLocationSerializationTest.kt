package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.content.model.DocumentBounds
import com.tl2333.novelvoicereader.content.model.DocumentLocation
import com.tl2333.novelvoicereader.content.model.DocumentLocationJson
import com.tl2333.novelvoicereader.content.model.SourceType
import org.junit.Test

class DocumentLocationSerializationTest {
    @Test
    fun roundTripsPdfAndWebFieldsDeterministically() {
        val location = DocumentLocation(
            sourceType = SourceType.PDF,
            sectionId = "page-2",
            blockId = "b7",
            charStart = 3,
            charEnd = 11,
            page = 2,
            bounds = DocumentBounds(1f, 2f, 30f, 40f),
            contentHash = "a".repeat(64),
            metadata = mapOf("column" to "1"),
        )
        val encoded = DocumentLocationJson.encode(location)
        assertThat(DocumentLocationJson.decode(encoded)).isEqualTo(location)
        assertThat(DocumentLocationJson.encode(DocumentLocationJson.decode(encoded))).isEqualTo(encoded)
    }
}

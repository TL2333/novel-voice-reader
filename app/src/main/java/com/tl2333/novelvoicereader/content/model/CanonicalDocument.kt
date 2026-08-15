package com.tl2333.novelvoicereader.content.model

enum class SourceType { EPUB, TXT, PDF, DOCX, DOC, WEB }

enum class BlockType {
    TITLE, HEADING, PARAGRAPH, QUOTE, LIST_ITEM, TABLE_TEXT, CAPTION, FOOTNOTE, PAGE_BREAK, OTHER,
}

data class DocumentBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite))
        require(right >= left && bottom >= top)
    }
}

/** A format-neutral source anchor. Fields not meaningful to a source type remain null. */
data class DocumentLocation(
    val sourceType: SourceType,
    val sectionId: String? = null,
    val blockId: String? = null,
    val charStart: Int? = null,
    val charEnd: Int? = null,
    val href: String? = null,
    val readiumLocatorJson: String? = null,
    val page: Int? = null,
    val bounds: DocumentBounds? = null,
    val paragraphIndex: Int? = null,
    val snapshotId: String? = null,
    val contentHash: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(charStart == null || charStart >= 0)
        require(charEnd == null || charEnd >= 0)
        require(charStart == null || charEnd == null || charEnd >= charStart)
        require(page == null || page >= 1)
        require(paragraphIndex == null || paragraphIndex >= 0)
    }
}

data class DocumentSection(
    val id: String,
    val title: String?,
    val order: Int,
    val anchor: DocumentLocation? = null,
) {
    init {
        require(id.isNotBlank())
        require(order >= 0)
    }
}

data class ContentBlock(
    val id: String,
    val sectionId: String,
    val type: BlockType,
    val text: String,
    val order: Int,
    val anchor: DocumentLocation,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank())
        require(sectionId.isNotBlank())
        require(order >= 0)
        require(anchor.blockId == null || anchor.blockId == id)
    }
}

data class CanonicalDocument(
    val id: String,
    val sourceType: SourceType,
    val title: String,
    val author: String?,
    val sourceUri: String,
    val contentHash: String,
    val sections: List<DocumentSection>,
    val blocks: List<ContentBlock>,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank())
        require(sourceUri.isNotBlank())
        require(contentHash.matches(Regex("[0-9a-fA-F]{64}")))
        require(sections.map(DocumentSection::id).distinct().size == sections.size)
        require(blocks.map(ContentBlock::id).distinct().size == blocks.size)
        require(sections.map(DocumentSection::order) == sections.map(DocumentSection::order).sorted())
        require(blocks.map(ContentBlock::order) == blocks.map(ContentBlock::order).sorted())
        val sectionIds = sections.map(DocumentSection::id).toSet()
        require(blocks.all { it.sectionId in sectionIds })
        require(blocks.all { it.anchor.sourceType == sourceType })
    }
}

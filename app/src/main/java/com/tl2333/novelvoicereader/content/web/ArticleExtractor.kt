package com.tl2333.novelvoicereader.content.web

import com.tl2333.novelvoicereader.content.model.BlockType
import com.tl2333.novelvoicereader.content.model.CanonicalDocument
import com.tl2333.novelvoicereader.content.model.ContentBlock
import com.tl2333.novelvoicereader.content.model.DocumentLocation
import com.tl2333.novelvoicereader.content.model.DocumentSection
import com.tl2333.novelvoicereader.content.model.SourceType
import com.tl2333.novelvoicereader.filesystem.Sha256
import java.io.ByteArrayInputStream
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class ExtractedArticle(val document: CanonicalDocument, val sanitizedHtml: String)

class ArticleExtractor {
    fun extract(url: String, htmlBytes: ByteArray, snapshotId: String): ExtractedArticle {
        val parsed = Jsoup.parse(ByteArrayInputStream(htmlBytes), null, url)
        parsed.select("script,style,noscript,nav,footer,aside,form,iframe,canvas,svg,[hidden],.comment,.comments,.related,.recommend,.advertisement,.ad,.share,.login,.cookie").remove()
        val candidates = linkedSetOf<Element>().apply {
            addAll(parsed.select("article,main,[role=main],.article-content,.post-content,.entry-content"))
            parsed.body()?.children()?.filterTo(this) { it.select("p").size >= 2 }
        }
        val article = candidates.maxByOrNull(::score)
            ?: throw WebImportException(WebImportErrorCode.EXTRACTION_FAILED, "No article candidate found")
        val readableLength = article.ownText().length + article.select("p").sumOf { it.text().length }
        if (readableLength < MIN_ARTICLE_CHARACTERS) {
            throw WebImportException(WebImportErrorCode.EXTRACTION_FAILED, "Extracted article is too short")
        }
        val title = article.selectFirst("h1")?.text()?.trim()?.takeIf(String::isNotEmpty)
            ?: parsed.title().trim().ifBlank { URI_TITLE_FALLBACK }
        val author = parsed.selectFirst("meta[name=author],meta[property=article:author]")?.attr("content")?.trim()?.takeIf(String::isNotEmpty)
        val sourceElements = article.select("h1,h2,h3,h4,p,blockquote,li,figcaption")
            .filter { element -> element.parents().none { parent -> parent !== article && parent.tagName() in setOf("p", "blockquote", "li") } }
        val blockTexts = sourceElements.mapNotNull { element ->
            element.text().trim().takeIf(String::isNotEmpty)?.let { element to it }
        }
        if (blockTexts.isEmpty()) throw WebImportException(WebImportErrorCode.EXTRACTION_FAILED, "Article has no readable blocks")
        val contentHash = Sha256.hash(blockTexts.joinToString("\n") { it.second })
        val section = DocumentSection("section-0", title, 0)
        val blocks = blockTexts.mapIndexed { index, (element, text) ->
            val blockId = "block-$index"
            ContentBlock(
                id = blockId,
                sectionId = section.id,
                type = when (element.tagName()) {
                    "h1" -> BlockType.TITLE
                    "h2", "h3", "h4" -> BlockType.HEADING
                    "blockquote" -> BlockType.QUOTE
                    "li" -> BlockType.LIST_ITEM
                    "figcaption" -> BlockType.CAPTION
                    else -> BlockType.PARAGRAPH
                },
                text = text,
                order = index,
                anchor = DocumentLocation(
                    SourceType.WEB,
                    sectionId = section.id,
                    blockId = blockId,
                    snapshotId = snapshotId,
                    contentHash = contentHash,
                ),
            )
        }
        return ExtractedArticle(
            CanonicalDocument(
                id = snapshotId,
                sourceType = SourceType.WEB,
                title = title,
                author = author,
                sourceUri = url,
                contentHash = contentHash,
                sections = listOf(section),
                blocks = blocks,
                metadata = mapOf("domain" to java.net.URI(url).host.orEmpty()),
            ),
            article.outerHtml(),
        )
    }

    private fun score(element: Element): Double {
        val textLength = element.text().length.toDouble()
        val paragraphs = element.select("p").size
        val linkText = element.select("a").sumOf { it.text().length }
        val punctuation = element.text().count { it in "。！？；，,.!?;:" }
        val hint = (element.id() + " " + element.className()).lowercase()
        val hintScore = when {
            listOf("article", "content", "post", "entry", "main").any(hint::contains) -> 500
            else -> 0
        }
        val noisePenalty = if (listOf("comment", "related", "recommend", "menu", "footer").any(hint::contains)) 2_000 else 0
        val linkDensity = if (textLength == 0.0) 1.0 else linkText / textLength
        return textLength + paragraphs * 120 + punctuation * 4 + hintScore - noisePenalty - linkDensity * 1_000
    }

    companion object {
        private const val MIN_ARTICLE_CHARACTERS = 80
        private const val URI_TITLE_FALLBACK = "Web article"
    }
}

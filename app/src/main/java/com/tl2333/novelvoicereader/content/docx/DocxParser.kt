package com.tl2333.novelvoicereader.content.docx

import com.tl2333.novelvoicereader.content.model.BlockType
import com.tl2333.novelvoicereader.filesystem.ArchiveLimits
import com.tl2333.novelvoicereader.filesystem.SafeArchive
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

data class DocxParagraph(
    val text: String,
    val type: BlockType,
    val paragraphIndex: Int,
    val pageBreakAfter: Boolean,
)

object DocxStyleResolver {
    fun blockType(styleId: String?, styleName: String?, numbered: Boolean, inTable: Boolean): BlockType {
        val style = listOfNotNull(styleId, styleName).joinToString(" ").lowercase()
        return when {
            inTable -> BlockType.TABLE_TEXT
            styleId.equals("title", ignoreCase = true) || styleName.equals("title", ignoreCase = true) ||
                style.contains("document title") || style.contains("文档标题") -> BlockType.TITLE
            style.contains("heading") || style.contains("标题") -> BlockType.HEADING
            numbered -> BlockType.LIST_ITEM
            else -> BlockType.PARAGRAPH
        }
    }
}

class DocxParser {
    fun parse(file: File): List<DocxParagraph> {
        SafeArchive.inspect(
            file,
            ArchiveLimits(
                maxEntries = 20_000,
                maxSingleFileBytes = 64L * 1024 * 1024,
                maxTotalBytes = 256L * 1024 * 1024,
                maxCompressionRatio = 150.0,
                maxPathLength = 512,
                maxPathDepth = 16,
            ),
        )
        ZipFile(file).use { zip ->
            requireNotNull(zip.getEntry("[Content_Types].xml")) { "DOCX has no [Content_Types].xml" }
            val documentEntry = requireNotNull(zip.getEntry("word/document.xml")) { "DOCX has no word/document.xml" }
            val styles = zip.getEntry("word/styles.xml")?.let { entry ->
                zip.getInputStream(entry).use(::parseStyles)
            }.orEmpty()
            return zip.getInputStream(documentEntry).use { parseDocument(it, styles) }
        }
    }

    private fun parseStyles(input: InputStream): Map<String, String> {
        val styles = linkedMapOf<String, String>()
        var styleId: String? = null
        secureParser().parse(InputSource(input), object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                when (element(localName, qName)) {
                    "style" -> styleId = value(attributes, "styleId")
                    "name" -> styleId?.let { id -> value(attributes, "val")?.let { styles[id] = it } }
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (element(localName, qName) == "style") styleId = null
            }
        })
        return styles
    }

    private fun parseDocument(input: InputStream, styles: Map<String, String>): List<DocxParagraph> {
        val output = mutableListOf<DocxParagraph>()
        val text = StringBuilder()
        var inParagraph = false
        var inText = false
        var tableDepth = 0
        var styleId: String? = null
        var numbered = false
        var pageBreak = false
        var paragraphIndex = -1
        secureParser().parse(InputSource(input), object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                when (element(localName, qName)) {
                    "tbl" -> tableDepth++
                    "p" -> {
                        paragraphIndex++
                        inParagraph = true
                        text.clear()
                        styleId = null
                        numbered = false
                        pageBreak = false
                    }
                    "pStyle" -> if (inParagraph) styleId = value(attributes, "val")
                    "numPr" -> if (inParagraph) numbered = true
                    "t" -> if (inParagraph) inText = true
                    "tab" -> if (inParagraph) text.append('\t')
                    "br" -> if (inParagraph) {
                        if (value(attributes, "type") == "page") pageBreak = true else text.append('\n')
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inParagraph && inText) text.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (element(localName, qName)) {
                    "t" -> inText = false
                    "p" -> {
                        val value = text.toString().trim()
                        if (value.isNotEmpty()) {
                            output += DocxParagraph(
                                text = value,
                                type = DocxStyleResolver.blockType(styleId, styles[styleId], numbered, tableDepth > 0),
                                paragraphIndex = paragraphIndex,
                                pageBreakAfter = pageBreak,
                            )
                        }
                        inParagraph = false
                    }
                    "tbl" -> tableDepth--
                }
            }
        })
        return output
    }

    private fun secureParser() = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        isValidating = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }.newSAXParser()

    private fun element(localName: String?, qName: String?): String =
        localName?.takeIf(String::isNotEmpty) ?: qName.orEmpty().substringAfter(':')

    private fun value(attributes: Attributes, localName: String): String? =
        attributes.getValue("http://schemas.openxmlformats.org/wordprocessingml/2006/main", localName)
            ?: attributes.getValue("w:$localName")
            ?: attributes.getValue(localName)
}

package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.content.docx.DocxImporter
import com.tl2333.novelvoicereader.content.model.BlockType
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocxParserTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun extractsTitleHeadingListTableAndPageBreak() {
        val file = temporaryFolder.root.resolve("fixture.docx")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.entry("[Content_Types].xml", """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""")
            zip.entry("word/styles.xml", """<?xml version="1.0"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:styleId="Title"><w:name w:val="Title"/></w:style><w:style w:styleId="Heading1"><w:name w:val="Heading 1"/></w:style></w:styles>""")
            zip.entry("word/document.xml", """<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t>测试文档</w:t></w:r></w:p><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>第一章</w:t></w:r></w:p><w:p><w:pPr><w:numPr/></w:pPr><w:r><w:t>列表项目</w:t></w:r><w:r><w:br w:type="page"/></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>表格文字</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>""")
        }
        val document = DocxImporter().import(file)
        assertThat(document.title).isEqualTo("测试文档")
        assertThat(document.blocks.map { it.type }).containsAtLeast(BlockType.TITLE, BlockType.HEADING, BlockType.LIST_ITEM, BlockType.TABLE_TEXT, BlockType.PAGE_BREAK)
        assertThat(document.blocks.first { it.text == "列表项目" }.anchor.paragraphIndex).isEqualTo(2)
    }

    private fun ZipOutputStream.entry(path: String, value: String) {
        putNextEntry(ZipEntry(path))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}

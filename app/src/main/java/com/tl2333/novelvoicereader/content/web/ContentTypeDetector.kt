package com.tl2333.novelvoicereader.content.web

import com.tl2333.novelvoicereader.content.importing.DocumentContentKind
import com.tl2333.novelvoicereader.content.importing.DocumentTypeDetector

enum class WebContentKind { HTML, TXT, PDF, DOCX, EPUB, UNSUPPORTED }

object ContentTypeDetector {
    fun detect(contentType: String?, bytes: ByteArray, urlPath: String = ""): WebContentKind {
        return when (DocumentTypeDetector.detectRemote(contentType, bytes, urlPath)) {
            DocumentContentKind.HTML -> WebContentKind.HTML
            DocumentContentKind.TXT -> WebContentKind.TXT
            DocumentContentKind.PDF -> WebContentKind.PDF
            DocumentContentKind.DOCX -> WebContentKind.DOCX
            DocumentContentKind.EPUB -> WebContentKind.EPUB
            DocumentContentKind.LEGACY_DOC, DocumentContentKind.UNSUPPORTED -> WebContentKind.UNSUPPORTED
        }
    }
}

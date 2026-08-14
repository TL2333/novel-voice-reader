package com.tl2333.novelvoicereader.content.docx

import java.io.File

class LegacyDocUnsupportedException(message: String) : Exception(message)

/** Explicitly isolated until an Android-compatible, locked HWPF build is verified. */
class LegacyDocImporter {
    fun import(file: File): Nothing {
        require(file.isFile && file.extension.equals("doc", ignoreCase = true))
        throw LegacyDocUnsupportedException(
            "Legacy .doc binary parsing is unavailable; convert the file to DOCX. DOCX support remains enabled.",
        )
    }
}

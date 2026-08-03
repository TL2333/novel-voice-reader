package com.tl2333.novelvoicereader.filesystem

import java.io.File

enum class EpubSignatureError {
    INVALID_ZIP,
    UNSAFE_PATH,
    MISSING_MIMETYPE,
    INVALID_MIMETYPE,
    MISSING_CONTAINER,
}

data class EpubSignatureResult(
    val isValid: Boolean,
    val error: EpubSignatureError? = null,
    val message: String? = null,
)

object EpubSignature {
    const val MIME_TYPE = "application/epub+zip"

    fun verify(file: File): EpubSignatureResult {
        val inspection = try {
            SafeArchive.inspect(file)
        } catch (error: UnsafeArchiveException) {
            val signatureError = when (error.violation) {
                ArchiveViolation.INVALID_PATH,
                ArchiveViolation.PATH_TRAVERSAL,
                ArchiveViolation.DUPLICATE_PATH,
                ArchiveViolation.ENTRY_TYPE_CONFLICT,
                -> EpubSignatureError.UNSAFE_PATH

                else -> EpubSignatureError.INVALID_ZIP
            }
            return EpubSignatureResult(false, signatureError, error.message)
        }
        val mimetype = inspection.entries.singleOrNull { it.normalizedPath == "mimetype" && !it.isDirectory }
            ?: return EpubSignatureResult(false, EpubSignatureError.MISSING_MIMETYPE, "Missing root mimetype entry")
        val type = runCatching {
            SafeArchive.readEntryBytes(file, mimetype.normalizedPath, 128).toString(Charsets.US_ASCII).trim()
        }.getOrElse { return EpubSignatureResult(false, EpubSignatureError.INVALID_MIMETYPE, it.message) }
        if (type != MIME_TYPE) {
            return EpubSignatureResult(false, EpubSignatureError.INVALID_MIMETYPE, "Unexpected EPUB media type")
        }
        if (inspection.entries.none {
                it.normalizedPath.equals("META-INF/container.xml", ignoreCase = false) && !it.isDirectory
            }
        ) {
            return EpubSignatureResult(false, EpubSignatureError.MISSING_CONTAINER, "Missing META-INF/container.xml")
        }
        return EpubSignatureResult(true)
    }
}

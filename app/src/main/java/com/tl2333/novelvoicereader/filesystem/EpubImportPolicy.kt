package com.tl2333.novelvoicereader.filesystem

/** Limits applied while copying an EPUB from an untrusted document provider. */
data class EpubImportLimits(
    val maximumFileBytes: Long = DEFAULT_MAXIMUM_FILE_BYTES,
    val freeSpaceReserveBytes: Long = DEFAULT_FREE_SPACE_RESERVE_BYTES,
) {
    init {
        require(maximumFileBytes > 0L) { "maximumFileBytes must be positive" }
        require(freeSpaceReserveBytes >= 0L) { "freeSpaceReserveBytes must not be negative" }
    }

    companion object {
        const val DEFAULT_MAXIMUM_FILE_BYTES: Long = 512L * 1024L * 1024L
        const val DEFAULT_FREE_SPACE_RESERVE_BYTES: Long = 256L * 1024L * 1024L
    }
}

enum class EpubImportRejection {
    EMPTY_SOURCE,
    FILE_TOO_LARGE,
    INSUFFICIENT_FREE_SPACE,
}

/** Pure capacity checks shared by the SAF metadata preflight and bounded copy loop. */
object EpubImportPolicy {
    fun beforeCopy(
        declaredSizeBytes: Long?,
        usableSpaceBytes: Long,
        limits: EpubImportLimits = EpubImportLimits(),
    ): EpubImportRejection? {
        val knownSize = declaredSizeBytes?.takeIf { it >= 0L }
        if (knownSize == 0L) return EpubImportRejection.EMPTY_SOURCE
        if (knownSize != null && knownSize > limits.maximumFileBytes) {
            return EpubImportRejection.FILE_TOO_LARGE
        }

        // Unknown-size providers remain supported. Requiring one byte beyond the reserve ensures
        // that the first non-empty chunk can be considered by beforeWrite().
        val bytesToWrite = knownSize ?: 1L
        return if (hasCapacity(usableSpaceBytes, bytesToWrite, limits.freeSpaceReserveBytes)) {
            null
        } else {
            EpubImportRejection.INSUFFICIENT_FREE_SPACE
        }
    }

    fun beforeWrite(
        copiedBytes: Long,
        nextChunkBytes: Int,
        usableSpaceBytes: Long,
        limits: EpubImportLimits = EpubImportLimits(),
    ): EpubImportRejection? {
        require(copiedBytes >= 0L) { "copiedBytes must not be negative" }
        require(nextChunkBytes >= 0) { "nextChunkBytes must not be negative" }
        if (nextChunkBytes == 0) return null

        val chunkBytes = nextChunkBytes.toLong()
        if (
            copiedBytes > limits.maximumFileBytes ||
            chunkBytes > limits.maximumFileBytes - copiedBytes
        ) {
            return EpubImportRejection.FILE_TOO_LARGE
        }
        return if (hasCapacity(usableSpaceBytes, chunkBytes, limits.freeSpaceReserveBytes)) {
            null
        } else {
            EpubImportRejection.INSUFFICIENT_FREE_SPACE
        }
    }

    fun afterCopy(
        copiedBytes: Long,
        limits: EpubImportLimits = EpubImportLimits(),
    ): EpubImportRejection? = when {
        copiedBytes <= 0L -> EpubImportRejection.EMPTY_SOURCE
        copiedBytes > limits.maximumFileBytes -> EpubImportRejection.FILE_TOO_LARGE
        else -> null
    }

    private fun hasCapacity(
        usableSpaceBytes: Long,
        bytesToWrite: Long,
        reserveBytes: Long,
    ): Boolean =
        usableSpaceBytes >= reserveBytes &&
            bytesToWrite <= usableSpaceBytes - reserveBytes
}

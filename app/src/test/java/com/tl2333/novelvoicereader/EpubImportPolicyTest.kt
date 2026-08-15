package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.filesystem.EpubImportLimits
import com.tl2333.novelvoicereader.filesystem.EpubImportPolicy
import com.tl2333.novelvoicereader.filesystem.EpubImportRejection
import org.junit.Test

class EpubImportPolicyTest {
    private val limits = EpubImportLimits(
        maximumFileBytes = 100L,
        freeSpaceReserveBytes = 25L,
    )

    @Test
    fun knownSizeRejectsEmptyOversizedAndInsufficientCapacity() {
        assertThat(EpubImportPolicy.beforeCopy(0L, 1_000L, limits))
            .isEqualTo(EpubImportRejection.EMPTY_SOURCE)
        assertThat(EpubImportPolicy.beforeCopy(101L, 1_000L, limits))
            .isEqualTo(EpubImportRejection.FILE_TOO_LARGE)
        assertThat(EpubImportPolicy.beforeCopy(80L, 104L, limits))
            .isEqualTo(EpubImportRejection.INSUFFICIENT_FREE_SPACE)
        assertThat(EpubImportPolicy.beforeCopy(80L, 105L, limits)).isNull()
    }

    @Test
    fun unknownProviderSizeIsAcceptedAndCheckedDuringStreaming() {
        assertThat(EpubImportPolicy.beforeCopy(null, 26L, limits)).isNull()
        assertThat(EpubImportPolicy.beforeCopy(-1L, 26L, limits)).isNull()
        assertThat(EpubImportPolicy.beforeCopy(null, 25L, limits))
            .isEqualTo(EpubImportRejection.INSUFFICIENT_FREE_SPACE)

        assertThat(EpubImportPolicy.beforeWrite(90L, 10, 35L, limits)).isNull()
        assertThat(EpubImportPolicy.beforeWrite(90L, 11, 1_000L, limits))
            .isEqualTo(EpubImportRejection.FILE_TOO_LARGE)
    }

    @Test
    fun eachChunkMustLeaveTheConfiguredReserve() {
        assertThat(EpubImportPolicy.beforeWrite(10L, 8, 32L, limits))
            .isEqualTo(EpubImportRejection.INSUFFICIENT_FREE_SPACE)
        assertThat(EpubImportPolicy.beforeWrite(10L, 8, 33L, limits)).isNull()
    }

    @Test
    fun completedCopyRejectsAnEmptyStream() {
        assertThat(EpubImportPolicy.afterCopy(0L, limits))
            .isEqualTo(EpubImportRejection.EMPTY_SOURCE)
        assertThat(EpubImportPolicy.afterCopy(1L, limits)).isNull()
    }
}

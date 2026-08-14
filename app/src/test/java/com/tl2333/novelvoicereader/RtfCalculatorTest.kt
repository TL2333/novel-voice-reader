package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.narration.RtfTracker
import org.junit.Test

class RtfCalculatorTest {
    @Test
    fun calculatesNearestRankPercentilesAndBoundsHistory() {
        val tracker = RtfTracker(capacity = 4)
        listOf(100L, 200L, 300L, 400L, 500L).forEach { tracker.record(it, 1_000) }
        val stats = requireNotNull(tracker.statistics())
        assertThat(stats.sampleCount).isEqualTo(4)
        assertThat(stats.p50).isWithin(0.0001).of(0.3)
        assertThat(stats.p90).isWithin(0.0001).of(0.5)
        assertThat(stats.p95).isWithin(0.0001).of(0.5)
    }
}

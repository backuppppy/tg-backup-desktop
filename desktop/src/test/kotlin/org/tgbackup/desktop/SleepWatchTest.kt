package org.tgbackup.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class SleepWatchTest {
    @Test
    fun gapUnderTwoMinutesIsZero() {
        val w = SleepWatch({ 0L })
        assertEquals(0L, w.gap(0, 30_000))
        assertEquals(0L, w.gap(0, 30_000 + 119_999))
    }

    @Test
    fun aTenMinuteGapIsReported() {
        val w = SleepWatch({ 0L })
        assertEquals(600_000L, w.gap(1_000, 1_000 + 30_000 + 600_000))
    }

    @Test
    fun checkMeasuresFromTheLastCheck() {
        var now = 0L
        val w = SleepWatch({ now })
        w.reset()
        now = 30_000
        assertEquals(0L, w.check())
        now += 30_000 + 600_000
        assertEquals(600_000L, w.check())
        now += 30_000
        assertEquals(0L, w.check())
    }
}

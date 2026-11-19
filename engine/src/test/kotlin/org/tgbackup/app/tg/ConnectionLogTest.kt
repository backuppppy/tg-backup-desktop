package org.tgbackup.app.tg

import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionLogTest {
    @Test
    fun unknownUntilTdlibReports() {
        assertEquals("לא ידוע", ConnectionLog().current())
        assertEquals("לא ידוע", ConnectionLog().since(0))
    }

    @Test
    fun nothingChangedShowsTheStateItWasIn() {
        var t = 1_000L
        val c = ConnectionLog { t }
        c.record("מחובר")
        t = 10_000
        assertEquals("מחובר", c.since(5_000))
    }

    @Test
    fun theChangesDuringTheSpanInOrderWithTheirSecond() {
        var t = 0L
        val c = ConnectionLog { t }
        c.record("מחובר")
        t = 10_000; c.record("ממתין לרשת")
        t = 70_000; c.record("מתחבר")
        t = 150_000; c.record("מחובר")
        assertEquals("מחובר ← ממתין לרשת (5 שנ') ← מתחבר (65 שנ') ← מחובר (145 שנ')", c.since(5_000))
        assertEquals("מחובר", c.current())
    }

    @Test
    fun keepsOnlyTheLastOnes() {
        var t = 0L
        val c = ConnectionLog(keep = 2) { t }
        repeat(5) { t = it * 1000L; c.record("s$it") }
        assertEquals("s3 (0 שנ') ← s4 (1 שנ')", c.since(3_000))
    }
}

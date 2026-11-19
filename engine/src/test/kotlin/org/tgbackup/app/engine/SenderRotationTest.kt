package org.tgbackup.app.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SenderRotationTest {
    private var t = 1_000_000L
    private fun rotation(size: Int) = SenderRotation(size) { t }
    private fun SenderRotation.idx(): Int = (pick() as SenderRotation.Pick.Account).index

    @Test
    fun roundRobin() {
        val r = rotation(3)
        assertEquals(listOf(0, 1, 2, 0), List(4) { r.idx() })
    }

    @Test
    fun restingSkipped() {
        val r = rotation(3)
        r.rest(1, t + 2000)
        assertEquals(listOf(0, 2, 0), List(3) { r.idx() })
        t += 2000
        assertEquals(listOf(1, 2, 0), List(3) { r.idx() })
    }

    @Test
    fun busySkippedThenHotStillWorks() {
        val r = rotation(3)
        r.busy(0, t + 10_000)
        assertEquals(listOf(1, 2, 1), List(3) { r.idx() })
        t += 10_000
        // Free again: it keeps its turns (hot is not idle), it only rests longer.
        assertEquals(listOf(2, 0, 1), List(3) { r.idx() })
        assertTrue(r.isHot(0))
        assertFalse(r.isHot(1))
        assertEquals(6000, r.restMs(0, 2000))
        assertEquals(5000, r.restMs(0, 1000))
        assertEquals(5000, r.restMs(0, 0))
        assertEquals(2000, r.restMs(1, 2000))
    }

    @Test
    fun hotWindowIsWaitClamped() {
        val r = rotation(2)
        r.busy(0, t + 1000)
        r.busy(1, t + 3_600_000)
        t += 1000 + 300_000 - 1
        assertTrue(r.isHot(0))
        t += 1
        assertFalse(r.isHot(0))
        assertEquals(2000, r.restMs(0, 2000))
        t = 1_000_000L + 3_600_000 + 1_800_000 - 1
        assertTrue(r.isHot(1))
        t += 1
        assertFalse(r.isHot(1))
    }

    @Test
    fun allRestingGivesWait() {
        val r = rotation(3)
        r.rest(0, t + 900)
        r.rest(1, t + 700)
        r.busy(2, t + 5000)
        assertEquals(SenderRotation.Pick.Wait(700), r.pick())
    }

    @Test
    fun allExcludedGivesNone() {
        val r = rotation(3)
        assertEquals(SenderRotation.Pick.None, r.pick(exclude = setOf(0, 1, 2)))
        assertEquals(SenderRotation.Pick.None, r.pick(usable = { false }))
        // Excluded ones are not waited for either.
        r.rest(0, t + 500)
        assertEquals(SenderRotation.Pick.None, r.pick(exclude = setOf(1, 2), usable = { it != 0 }))
    }

    @Test
    fun singleAccountAlwaysZero() {
        val r = rotation(1)
        assertEquals(listOf(0, 0, 0), List(3) { r.idx() })
        r.rest(0, t + 300)
        assertEquals(SenderRotation.Pick.Wait(300), r.pick())
    }

    @Test
    fun aShortWaitDoesNotCutALongHotWindow() {
        var t = 0L
        val r = SenderRotation(1) { t }
        r.busy(0, 20 * 60_000L)
        r.busy(0, 3_000L)
        t = 30 * 60_000L
        kotlin.test.assertTrue(r.isHot(0))
    }

    @Test
    fun eachAccountSendsItsTurnInARowThenRests() {
        var now = 0L
        val r = SenderRotation(3, now = { now }, turnSize = 3)
        val order = mutableListOf<Int>()
        repeat(9) {
            val i = (r.pick() as SenderRotation.Pick.Account).index
            order += i
            r.sent(i, restMs = 2_000)
        }
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 2, 2, 2), order)
        // All three rest now: the first is free again after its rest.
        assertEquals(SenderRotation.Pick.Wait(2_000), r.pick())
        now = 2_000
        assertEquals(SenderRotation.Pick.Account(0), r.pick())
    }

    @Test
    fun aFloodWaitInTheMiddleOfATurnPassesItOn() {
        var now = 0L
        val r = SenderRotation(2, now = { now }, turnSize = 3)
        assertEquals(SenderRotation.Pick.Account(0), r.pick())
        r.sent(0, 2_000)
        r.busy(0, 60_000)
        assertEquals(SenderRotation.Pick.Account(1), r.pick())
        r.sent(1, 2_000)
        assertEquals(SenderRotation.Pick.Account(1), r.pick())
    }

    @Test
    fun aFailedAccountIsSkippedEvenInItsTurn() {
        val r = SenderRotation(2, now = { 0L }, turnSize = 3)
        r.pick(); r.sent(0, 1_000)
        assertEquals(SenderRotation.Pick.Account(1), r.pick(exclude = setOf(0)))
    }

    @Test
    fun turnOfOneIsTheOldRoundRobin() {
        var now = 0L
        val r = SenderRotation(2, now = { now }, turnSize = 1)
        assertEquals(SenderRotation.Pick.Account(0), r.pick()); r.sent(0, 0)
        assertEquals(SenderRotation.Pick.Account(1), r.pick()); r.sent(1, 0)
        assertEquals(SenderRotation.Pick.Account(0), r.pick())
    }

    @Test
    fun botRules() {
        val user = org.tgbackup.app.data.Account(id = "u", phone = "+1")
        val bot = org.tgbackup.app.data.Account(id = "b", botToken = "1:x")
        kotlin.test.assertNull(botRule(listOf(user, bot), viaTakeout = false))
        kotlin.test.assertNotNull(botRule(listOf(bot), viaTakeout = false))
        kotlin.test.assertNotNull(botRule(listOf(user, bot), viaTakeout = true))
        kotlin.test.assertNull(botRule(listOf(user), viaTakeout = true))
        assertEquals(20, senderLimit(25, isBot = true))
        assertEquals(20, senderLimit(0, isBot = true))
        assertEquals(10, senderLimit(10, isBot = true))
        assertEquals(25, senderLimit(25, isBot = false))
        assertEquals(0, senderLimit(0, isBot = false))
    }
}

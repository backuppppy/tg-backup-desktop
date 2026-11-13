package org.tgbackup.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `TGB_SMOKE_HOLD_MS`: how long `--smoke` keeps the loaded window (CI takes two screenshots in that time). */
class AppSmokeTest {
    @Test
    fun withoutTheVariableTheHoldIsTheDefault() {
        for (v in listOf(null, "", "  ", "abc", "-5", "0")) assertEquals(AppSmoke.HOLD_MS, AppSmoke.holdMs(v), "value=$v")
    }

    @Test
    fun aValueIsUsedAndCappedAtOneMinute() {
        assertEquals(20_000L, AppSmoke.holdMs("20000"))
        assertEquals(1_000L, AppSmoke.holdMs(" 1000 "))
        assertEquals(AppSmoke.MAX_HOLD_MS, AppSmoke.holdMs("999999999"))
    }
}

/** `--smoke` turns the phone access on and waits (up to 15 s) for a state other than Off: `REMOTE_STATE <state>`. */
class SmokeRemoteTest {
    private class FakeClock {
        var t = 0L
        val sleeps = mutableListOf<Long>()
        fun sleep(ms: Long) { sleeps += ms; t += ms }
    }

    @Test
    fun theFirstStateOtherThanOffIsReturned() {
        val clock = FakeClock()
        val states = ArrayDeque(listOf("Off", "Off", "NotInstalled", "Running"))
        val s = SmokeRemote.awaitState({ states.removeFirst() }, now = { clock.t }, sleep = clock::sleep)
        assertEquals("NotInstalled", s)
        assertEquals(2, clock.sleeps.size)
    }

    @Test
    fun aStateAtOnceNeedsNoWait() {
        val clock = FakeClock()
        assertEquals("NotInstalled", SmokeRemote.awaitState({ "NotInstalled" }, now = { clock.t }, sleep = clock::sleep))
        assertEquals(emptyList(), clock.sleeps)
    }

    @Test
    fun stillOffAfterFifteenSecondsIsOff() {
        val clock = FakeClock()
        var reads = 0
        val s = SmokeRemote.awaitState({ reads++; "Off" }, now = { clock.t }, sleep = clock::sleep)
        assertEquals("Off", s)
        assertTrue(clock.t in SmokeRemote.WAIT_MS..SmokeRemote.WAIT_MS + SmokeRemote.STEP_MS, "t=${clock.t}")
        assertTrue(reads > 1)
    }

    @Test
    fun theLineIsStateAfterTheTag() {
        assertEquals("REMOTE_STATE NotInstalled", SmokeRemote.line("NotInstalled"))
    }
}

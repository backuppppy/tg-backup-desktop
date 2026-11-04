package org.tgbackup.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class SleepGuardTest {
    private class Recorder : PowerRequests {
        val calls = mutableListOf<Boolean>()
        override fun keepAwake(on: Boolean) {
            calls += on
        }
    }

    @Test
    fun firstActiveTurnsOn() {
        val r = Recorder()
        SleepGuard(r).update(true)
        assertEquals(listOf(true), r.calls)
    }

    @Test
    fun repeatedStateIsNotResent() {
        val r = Recorder()
        val g = SleepGuard(r)
        listOf(true, true, false, false).forEach { g.update(it) }
        assertEquals(listOf(true, false), r.calls)
    }

    @Test
    fun startsInactive() {
        val r = Recorder()
        SleepGuard(r).update(false)
        assertEquals(emptyList(), r.calls)
    }
}

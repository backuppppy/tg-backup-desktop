package org.tgbackup.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test
    fun aFailedCallIsRetried() {
        val calls = mutableListOf<Boolean>()
        var failNext = true
        val g = SleepGuard { on ->
            calls += on
            if (failNext) {
                failNext = false
                error("power API down")
            }
        }
        assertFailsWith<IllegalStateException> { g.update(true) }
        g.update(true)
        g.update(true)
        assertEquals(listOf(true, true), calls)
    }
}

class ReasonContextTest {
    /** The layout PowerCreateRequest reads: Version, Flags, then the union whose first member is the string. */
    @Test
    fun simpleStringLayout() {
        val ctx = ReasonContext("TG Backup: משימה פעילה")
        val p = ctx.pointer
        assertEquals(0, p.getInt(0))
        assertEquals(1, p.getInt(4))
        val ptr = com.sun.jna.Native.POINTER_SIZE
        // The union: as big as the detailed form (module, id, count, strings), aligned to a pointer.
        assertEquals(8 + ptr + 4 + 4 + ptr, ctx.size())
        assertEquals("TG Backup: משימה פעילה", p.getPointer(8).getWideString(0))
    }
}

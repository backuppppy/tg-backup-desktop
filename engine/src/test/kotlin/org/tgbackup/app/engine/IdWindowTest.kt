package org.tgbackup.app.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdWindowTest {
    @Test
    fun nextIdsUpToMaxAndLatest() {
        assertEquals(101L..150L, idWindow(after = 100, latest = 1000, max = 50))
        assertEquals(101L..120L, idWindow(after = 100, latest = 120, max = 50))
        assertEquals(1L..1L, idWindow(after = 0, latest = 1, max = 50))
    }

    @Test
    fun caughtUpIsEmpty() {
        assertTrue(idWindow(after = 120, latest = 120, max = 50).isEmpty())
        assertTrue(idWindow(after = 130, latest = 120, max = 50).isEmpty())
    }

    @Test
    fun serverIdsBecomeTdlibIds() {
        assertEquals(listOf(5L shl 20, 6L shl 20), tdlibIds(5L..6L).toList())
    }
}

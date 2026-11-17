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

    @Test
    fun sliceSizeFollowsTheLimit() {
        assertEquals(25, sliceSize(25))
        assertEquals(40, sliceSize(40))
        assertEquals(25, sliceSize(0))
        assertEquals(100, sliceSize(500))
        assertEquals(25, sliceSize(-3))
        assertEquals(1, sliceSize(1))
    }

    @Test
    fun aSavedWaveKeepsItsSize() {
        assertEquals(25, waveSliceSize(0))
        assertEquals(40, waveSliceSize(40))
    }
}

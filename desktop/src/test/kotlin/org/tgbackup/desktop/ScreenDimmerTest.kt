package org.tgbackup.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenDimmerTest {
    private class FakeBrightness(var level: Int?) : Brightness {
        val sets = mutableListOf<Int>()
        var reads = 0
        var failSet = false
        override fun read(): Int? = level.also { reads++ }
        override fun set(level: Int) {
            if (failSet) throw IllegalStateException("WMI failed")
            sets += level
            this.level = level
        }
    }

    private class FakeIdle(var idle: Long, var timeout: Long = 60_000, var presenting: Boolean = false) : IdleClock {
        override fun idleMs(): Long = idle
        override fun displayTimeoutMs(): Long = timeout
        override fun presenting(): Boolean = presenting
    }

    private fun file(): File = Files.createTempDirectory("dimmer").resolve(ScreenDimmer.FILE_NAME).toFile()

    @Test
    fun dimsAfterTimeout() {
        val f = file()
        val b = FakeBrightness(70)
        ScreenDimmer(f, b, FakeIdle(60_000)).tick(true)
        assertEquals(listOf(0), b.sets)
        assertEquals("70", f.readText().trim())
    }

    @Test
    fun notBeforeTimeout() {
        val b = FakeBrightness(70)
        ScreenDimmer(file(), b, FakeIdle(59_999)).tick(true)
        assertEquals(emptyList(), b.sets)
    }

    @Test
    fun inputRestores() {
        val f = file()
        val b = FakeBrightness(70)
        val idle = FakeIdle(60_000)
        val d = ScreenDimmer(f, b, idle)
        d.tick(true)
        idle.idle = 1_000
        d.tick(true)
        assertEquals(listOf(0, 70), b.sets)
        assertFalse(f.exists())
    }

    @Test
    fun stopRestores() {
        val f = file()
        val b = FakeBrightness(70)
        val d = ScreenDimmer(f, b, FakeIdle(60_000))
        d.tick(true)
        d.tick(false)
        assertEquals(listOf(0, 70), b.sets)
        assertFalse(f.exists())
    }

    @Test
    fun neverWhenTimeoutZero() {
        val b = FakeBrightness(70)
        ScreenDimmer(file(), b, FakeIdle(Long.MAX_VALUE / 2, timeout = 0)).tick(true)
        assertEquals(emptyList(), b.sets)
    }

    @Test
    fun neverSavesOwnDim() {
        val f = file()
        val b = FakeBrightness(70)
        val d = ScreenDimmer(f, b, FakeIdle(60_000))
        d.tick(true)
        d.tick(true)
        assertEquals(listOf(0), b.sets)
        assertEquals(1, b.reads)
        assertEquals("70", f.readText().trim())
    }

    @Test
    fun noPanelNoDim() {
        val f = file()
        val b = FakeBrightness(null)
        val lines = mutableListOf<String>()
        val d = ScreenDimmer(f, b, FakeIdle(60_000), log = { lines += it })
        d.tick(true)
        d.tick(true)
        assertEquals(emptyList(), b.sets)
        assertFalse(f.exists())
        assertEquals(1, lines.size)
        // No panel is remembered until the work stops: no PowerShell every 2 s.
        assertEquals(1, b.reads)
    }

    @Test
    fun recoverRestoresSaved() {
        val f = file()
        f.writeText("55")
        val b = FakeBrightness(0)
        assertTrue(ScreenDimmer(f, b, FakeIdle(0)).recover())
        assertEquals(listOf(55), b.sets)
        assertFalse(f.exists())
    }

    @Test
    fun failedRestoreKeepsFile() {
        val f = file()
        f.writeText("55")
        val b = FakeBrightness(0).apply { failSet = true }
        assertFailsWith<IllegalStateException> { ScreenDimmer(f, b, FakeIdle(0)).restore() }
        assertTrue(f.exists())
    }

    @Test
    fun corruptFileRestoresFull() {
        val f = file()
        f.writeText("")
        val b = FakeBrightness(0)
        val lines = mutableListOf<String>()
        assertTrue(ScreenDimmer(f, b, FakeIdle(0), log = { lines += it }).recover())
        assertEquals(listOf(100), b.sets)
        assertFalse(f.exists())
        assertEquals(1, lines.size)
    }

    @Test
    fun zeroLevelNotDimmed() {
        val f = file()
        val b = FakeBrightness(0)
        ScreenDimmer(f, b, FakeIdle(60_000)).tick(true)
        assertEquals(emptyList(), b.sets)
        assertFalse(f.exists())
    }

    @Test
    fun presentingNoDim() {
        val b = FakeBrightness(70)
        ScreenDimmer(file(), b, FakeIdle(60_000, presenting = true)).tick(true)
        assertEquals(emptyList(), b.sets)
    }

    @Test
    fun failedDimForgetsAndBacksOff() {
        val f = file()
        var now = 0L
        val b = FakeBrightness(70).apply { failSet = true }
        val d = ScreenDimmer(f, b, FakeIdle(60_000), now = { now })
        assertFailsWith<IllegalStateException> { d.tick(true) }
        assertFalse(f.exists())
        assertFalse(d.isDimmed)
        b.failSet = false
        now = 59_000
        d.tick(true)
        assertEquals(emptyList(), b.sets)
        now = 60_000
        d.tick(true)
        assertEquals(listOf(0), b.sets)
    }

    @Test
    fun failedRestoreBacksOff() {
        val f = file()
        var now = 0L
        val b = FakeBrightness(70)
        val idle = FakeIdle(60_000)
        val d = ScreenDimmer(f, b, idle, now = { now })
        d.tick(true)
        b.failSet = true
        idle.idle = 0
        assertFailsWith<IllegalStateException> { d.tick(true) }
        b.failSet = false
        now = 30_000
        d.tick(true)
        assertEquals(listOf(0), b.sets)
        now = 60_000
        d.tick(true)
        assertEquals(listOf(0, 70), b.sets)
        assertFalse(f.exists())
    }
}

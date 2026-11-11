package org.tgbackup.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SleepOverrideTest {
    /** The power scheme in memory: what Windows would hold. */
    private class FakeSettings(var current: SleepTimeouts) : PowerSettings {
        val writes = mutableListOf<SleepTimeouts>()
        override fun read(): SleepTimeouts = current
        override fun write(t: SleepTimeouts) {
            writes += t
            current = t
        }
    }

    private val user = SleepTimeouts(acStandby = 1800, dcStandby = 600, acHibernate = 7200, dcHibernate = 3600)
    private val never = SleepTimeouts(0, 0, 0, 0)

    private fun file(): File = Files.createTempDirectory("sleep-override").resolve("power-restore.json").toFile()

    @Test
    fun holdSavesOnceAndWritesZeros() {
        val f = file()
        val s = FakeSettings(user)
        SleepOverride(f, s).hold()
        assertTrue(f.exists())
        assertEquals(never, s.current)
        assertEquals(listOf(never), s.writes)
    }

    @Test
    fun releaseRestoresAndDeletes() {
        val f = file()
        val s = FakeSettings(user)
        val logs = mutableListOf<String>()
        val o = SleepOverride(f, s, logs::add)
        o.hold()
        assertTrue(o.release())
        assertEquals(user, s.current)
        assertFalse(f.exists())
        assertEquals(listOf("הגדרת השינה של Windows הוחזרה למצבה הקודם"), logs)
        // Nothing held: a second release changes nothing.
        assertFalse(o.release())
        assertEquals(2, s.writes.size)
    }

    @Test
    fun holdTwiceKeepsTheFirstSavedValues() {
        val f = file()
        val s = FakeSettings(user)
        val o = SleepOverride(f, s)
        o.hold()
        // Something set the scheme to "never" meanwhile (our own zeros): the user's values stay the saved ones.
        o.hold()
        assertEquals(never, s.current)
        o.release()
        assertEquals(user, s.current)
    }

    @Test
    fun recoverAfterACrashRestores() {
        val f = file()
        val s = FakeSettings(user)
        SleepOverride(f, s).hold()
        // The program died while holding: a new launch (a new instance) restores.
        val logs = mutableListOf<String>()
        assertTrue(SleepOverride(f, s, logs::add).recover())
        assertEquals(user, s.current)
        assertFalse(f.exists())
        assertEquals(1, logs.size)
    }

    @Test
    fun recoverWithoutAFileDoesNothing() {
        val s = FakeSettings(user)
        assertFalse(SleepOverride(file(), s).recover())
        assertTrue(s.writes.isEmpty())
    }

    @Test
    fun aSettingMissingOnThisPcIsNeverWritten() {
        val f = file()
        val s = FakeSettings(user.copy(acHibernate = -1, dcHibernate = -1))
        val o = SleepOverride(f, s)
        o.hold()
        assertEquals(SleepTimeouts(0, 0, -1, -1), s.current)
        o.release()
        assertEquals(user.copy(acHibernate = -1, dcHibernate = -1), s.current)
    }

    @Test
    fun aCorruptFileIsKeptAsBadAndHoldSavesAgain() {
        val f = file()
        f.writeText("{not json")
        val s = FakeSettings(user)
        val warnings = mutableListOf<String>()
        val o = SleepOverride(f, s, warn = warnings::add)
        o.hold()
        val bad = File(f.parentFile, "power-restore.json.bad")
        assertEquals("{not json", bad.readText())
        assertEquals(1, warnings.size)
        assertEquals(never, s.current)
        assertTrue(o.release())
        assertEquals(user, s.current)
    }

    @Test
    fun recoverWithACorruptFileKeepsItAndRestoresNothing() {
        val f = file()
        f.writeText("{not json")
        val s = FakeSettings(user)
        val warnings = mutableListOf<String>()
        assertFalse(SleepOverride(f, s, warn = warnings::add).recover())
        assertFalse(f.exists())
        assertTrue(File(f.parentFile, "power-restore.json.bad").exists())
        assertEquals(1, warnings.size)
        assertTrue(s.writes.isEmpty())
    }
}

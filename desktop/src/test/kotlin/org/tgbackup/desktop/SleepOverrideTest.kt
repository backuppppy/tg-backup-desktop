package org.tgbackup.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SleepOverrideTest {
    /** The power schemes in memory: what Windows would hold; [current] is the active scheme's values. */
    private class FakeSettings(start: SleepTimeouts) : PowerSettings {
        val schemes = mutableMapOf(SCHEME_A to start)
        var active = SCHEME_A
        val writes = mutableListOf<SleepTimeouts>()
        var current: SleepTimeouts
            get() = schemes.getValue(active)
            set(v) { schemes[active] = v }
        override fun activeScheme(): String = active
        override fun read(): SleepTimeouts = current
        override fun write(t: SleepTimeouts, scheme: String?) {
            writes += t
            schemes[scheme?.takeIf { it in schemes } ?: active] = t
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

    @Test
    fun holdSavesTheActiveScheme() {
        val f = file()
        SleepOverride(f, FakeSettings(user)).hold()
        assertTrue(f.readText().contains("\"scheme\":\"$SCHEME_A\""), f.readText())
    }

    @Test
    fun releaseWritesToTheSchemeThatWasActiveAtHold() {
        val f = file()
        val s = FakeSettings(user)
        val o = SleepOverride(f, s)
        o.hold()
        // The user switched to another scheme while work ran.
        val other = SleepTimeouts(60, 60, 60, 60)
        s.schemes[SCHEME_B] = other
        s.active = SCHEME_B
        assertTrue(o.release())
        assertEquals(user, s.schemes[SCHEME_A])
        assertEquals(other, s.schemes[SCHEME_B])
    }

    @Test
    fun releaseWritesToTheActiveSchemeWhenTheSavedOneIsGone() {
        val f = file()
        val s = FakeSettings(user)
        val o = SleepOverride(f, s)
        o.hold()
        s.schemes.remove(SCHEME_A)
        s.schemes[SCHEME_B] = SleepTimeouts(60, 60, 60, 60)
        s.active = SCHEME_B
        assertTrue(o.release())
        assertEquals(user, s.schemes[SCHEME_B])
        assertFalse(SCHEME_A in s.schemes)
    }

    @Test
    fun anOldFileWithoutASchemeIsRestoredToTheActiveScheme() {
        val f = file()
        f.writeText("""{"acStandby":1800,"dcStandby":600,"acHibernate":7200,"dcHibernate":3600}""")
        val s = FakeSettings(never)
        assertTrue(SleepOverride(f, s).recover())
        assertEquals(user, s.current)
        assertFalse(f.exists())
    }

    private companion object {
        const val SCHEME_A = "381b4222-f694-41f0-9685-ff5bb260df2e"
        const val SCHEME_B = "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c"
    }
}

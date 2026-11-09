package org.tgbackup.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutostartTest {
    /** An in-memory Run value that records every write. */
    private class FakeRunKey(var value: String? = null) : RunKey {
        val writes = mutableListOf<String>()
        override fun get(): String? = value
        override fun set(command: String) {
            writes += "set $command"
            value = command
        }
        override fun remove() {
            writes += "remove"
            value = null
        }
    }

    private val exe = "C:\\TG\\TGBackup.exe"

    @Test
    fun onWritesTheQuotedExeWithMinimized() {
        val key = FakeRunKey()
        Autostart(key, exe).apply(true)
        assertEquals("\"C:\\TG\\TGBackup.exe\" --minimized", key.value)
        // Already right: nothing is written again.
        Autostart(key, exe).apply(true)
        assertEquals(1, key.writes.size, "${key.writes}")
    }

    @Test
    fun aChangedExeIsRewritten() {
        val key = FakeRunKey("\"C:\\Old\\TGBackup.exe\" --minimized")
        Autostart(key, exe).apply(true)
        assertEquals("\"C:\\TG\\TGBackup.exe\" --minimized", key.value)
    }

    @Test
    fun offRemovesTheValueOnlyWhenPresent() {
        val key = FakeRunKey("\"C:\\TG\\TGBackup.exe\" --minimized")
        Autostart(key, exe).apply(false)
        assertNull(key.value)
        Autostart(key, exe).apply(false)
        assertEquals(listOf("remove"), key.writes)
    }

    @Test
    fun noExeTouchesNothing() {
        val key = FakeRunKey("\"C:\\TG\\TGBackup.exe\" --minimized")
        Autostart(key, null).apply(true)
        Autostart(key, null).apply(false)
        Autostart(key, "").apply(true)
        assertEquals(emptyList(), key.writes)
    }

    @Test
    fun smokeNeverTouchesTheRealRunKey() {
        val key = FakeRunKey("\"C:\\TG\\TGBackup.exe\" --minimized")
        val real = Autostart(key, exe)
        // Under --smoke the real Autostart is never even built; NONE writes nothing.
        val chosen = Autostart.forLaunch(smoke = true) { error("the real Run key was used") }
        chosen.apply(false)
        chosen.apply(true)
        assertEquals(emptyList(), key.writes)
        assertEquals(real, Autostart.forLaunch(smoke = false) { real })
    }
}

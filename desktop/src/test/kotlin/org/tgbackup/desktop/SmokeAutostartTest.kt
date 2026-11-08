package org.tgbackup.desktop

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.tgbackup.desktop.Autostart
import org.tgbackup.desktop.DesktopApp
import org.tgbackup.desktop.PowerRequests
import org.tgbackup.desktop.RunKey
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeAutostartTest {
    private class FakeRunKey(var value: String?) : RunKey {
        val changes = mutableListOf<String>()
        override fun get(): String? = value
        override fun set(command: String) {
            changes += "set:$command"
            value = command
        }
        override fun remove() {
            changes += "remove"
            value = null
        }
    }

    @Test
    fun temporarySmokePreferencesNeverModifyTheUsersRunValue() {
        val key = FakeRunKey("\"C:\\TG\\TGBackup.exe\" --minimized")
        val app = DesktopApp(
            Files.createTempDirectory("smoke-autostart").resolve("TGBackup-smoke"),
            PowerRequests { },
            Autostart(key, "C:\\TG\\TGBackup.exe"),
            manageAutostart = false,
        )
        try {
            app.start()
            runBlocking { delay(250) }
            assertEquals("\"C:\\TG\\TGBackup.exe\" --minimized", key.value)
            assertEquals(emptyList(), key.changes)
        } finally {
            app.close()
        }
    }
}

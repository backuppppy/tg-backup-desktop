package org.tgbackup.desktop

import org.tgbackup.app.data.Job
import org.tgbackup.app.data.Store
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAppTest {
    private class Recorder : PowerRequests {
        val calls: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
        override fun keepAwake(on: Boolean) {
            calls += on
        }
    }

    /** A data dir with a Hebrew name and a space, not created yet (DesktopApp must create it). */
    private fun dataDir(): Path = Files.createTempDirectory("desktop-app").resolve("תיקייה עם רווח")

    @Test
    fun startAndCloseOnAnEmptyDirWritesNothingSurprising() {
        val dir = dataDir()
        val app = DesktopApp(dir, Recorder())
        app.start()
        app.close()
        app.close()
        assertTrue(Files.isDirectory(dir))
        assertTrue(app.store.config.value.jobs.isEmpty())
        assertTrue(app.store.config.value.autoResume.isEmpty())
    }

    @Test
    fun closeKeepsAutoResume() {
        val dir = dataDir()
        Files.createDirectories(dir)
        Store(dir.toFile()).updateConfig {
            it.copy(jobs = listOf(Job(id = "j1", source = "s", target = "t")), autoResume = setOf("j1"))
        }
        val app = DesktopApp(dir, Recorder())
        app.start()
        assertFalse(app.jobs.isRunning("j1"))
        app.close()
        assertEquals(setOf("j1"), Store(dir.toFile()).config.value.autoResume)
    }

    @Test
    fun noAccountsMeansNoTdlibClients() {
        val app = DesktopApp(dataDir(), Recorder())
        try {
            app.start()
            assertTrue(app.accounts.clients.value.isEmpty())
        } finally {
            app.close()
        }
    }

    @Test
    fun idleAppDoesNotKeepThePcAwake() {
        val power = Recorder()
        val app = DesktopApp(dataDir(), power)
        app.start()
        app.close()
        assertFalse(true in power.calls, "power calls: ${power.calls}")
    }

    @Test
    fun aFailingPowerApiDoesNotBreakClose() {
        val app = DesktopApp(dataDir(), PowerRequests { error("power API down") })
        app.start()
        app.close()
        app.close()
    }
}

package org.tgbackup.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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

    @Test
    fun aLazyNotStartedExportCountsAsActiveAndIsNotStartedByCollecting() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { release.await() }
        val first = CompletableDeferred<Boolean>()
        val values = mutableListOf<Boolean>()
        val collector = launch(Dispatchers.Default) {
            activeWhileRunning(job).collect { v ->
                synchronized(values) { values += v }
                first.complete(v)
            }
        }
        assertTrue(withTimeout(5_000) { first.await() }, "first value must be true for a not-started job")
        repeat(20) { yield() }
        assertFalse(job.isActive, "collecting must not start the lazy job")
        assertFalse(job.isCompleted)
        job.start()
        release.complete(Unit)
        withTimeout(5_000) { collector.join() }
        assertEquals(listOf(true, false), synchronized(values) { values.toList() })
    }

    @Test
    fun aCompletedExportIsInactive() = runBlocking {
        val job = launch { }
        job.join()
        assertEquals(listOf(false), withTimeout(5_000) { activeWhileRunning(job).toList() }.distinct())
    }
}

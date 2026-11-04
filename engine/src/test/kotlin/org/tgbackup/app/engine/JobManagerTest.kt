package org.tgbackup.app.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.tgbackup.app.data.Job
import org.tgbackup.app.data.Store
import org.tgbackup.app.tg.AccountManager
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun dataDir(): File =
        Files.createTempDirectory("jobs").resolve("תיקייה עם רווח").toFile().apply { mkdirs() }

    private fun manager(store: Store, dir: File): JobManager {
        val log = LogBus(dir)
        // No account is configured, so no job reaches the runner and exports is never invoked.
        return JobManager(store, AccountManager(dir, log), log, scope) { error("unused") }
    }

    @Test
    fun startRejectsAJobWithoutAccounts() {
        val dir = dataDir()
        val store = Store(dir)
        store.updateConfig { it.copy(jobs = listOf(Job(id = "j1", source = "src", target = "dst"))) }
        val jobs = manager(store, dir)

        assertEquals("לא נבחרו חשבונות למשימה", jobs.start("j1"))
        assertTrue(store.config.value.autoResume.isEmpty())
        assertFalse(jobs.isRunning("j1"))
    }

    @Test
    fun startRejectsMissingSourceOrTarget() {
        val dir = dataDir()
        val store = Store(dir)
        store.updateConfig {
            it.copy(
                jobs = listOf(
                    Job(id = "nosrc", target = "dst", accountIds = listOf("a")),
                    Job(id = "nodst", source = "src", accountIds = listOf("a")),
                ),
            )
        }
        val jobs = manager(store, dir)

        assertEquals("חסר מקור או יעד", jobs.start("nosrc"))
        assertEquals("חסר מקור או יעד", jobs.start("nodst"))
        assertTrue(store.config.value.autoResume.isEmpty())
    }

    @Test
    fun restoreIgnoresDeletedJobs() {
        val dir = dataDir()
        val store = Store(dir)
        store.updateConfig { it.copy(autoResume = setOf("gone")) }
        val jobs = manager(store, dir)

        jobs.restore()

        assertFalse(jobs.isRunning("gone"))
    }

    @Test
    fun stopRemovesAutoResume() {
        val dir = dataDir()
        val store = Store(dir)
        store.updateConfig { it.copy(autoResume = setOf("j1"), jobs = listOf(Job(id = "j1"))) }
        val jobs = manager(store, dir)

        jobs.stop("j1")

        assertTrue(store.config.value.autoResume.isEmpty())
    }
}

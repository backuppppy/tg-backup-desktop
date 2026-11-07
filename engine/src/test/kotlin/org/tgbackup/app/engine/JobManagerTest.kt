package org.tgbackup.app.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.tgbackup.app.data.Job
import org.tgbackup.app.data.Progress
import org.tgbackup.app.data.Store
import org.tgbackup.app.tg.AccountManager
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun dataDir(): File =
        Files.createTempDirectory("jobs").resolve("תיקייה עם רווח").toFile().apply { mkdirs() }

    private fun manager(store: Store, dir: File, latestTick: Long = 3_600_000): JobManager {
        val log = LogBus(dir)
        // No account is configured, so no job reaches the runner and exports is never invoked.
        return JobManager(store, AccountManager(dir, log), log, scope, runTick = 50, latestTick = latestTick) { error("unused") }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for: $what")
            Thread.sleep(20)
        }
    }

    /**
     * A job that runs without a configured account: the runner never starts, the job stays in its
     * "restart in 15 s" loop, which is enough for the run clock.
     */
    private fun storeWithIdleJob(dir: File, progress: Progress = Progress()): Store {
        val store = Store(dir)
        store.updateConfig { it.copy(jobs = listOf(Job(id = "j1", source = "src", target = "dst", accountIds = listOf("a")))) }
        store.saveProgress("j1") { progress }
        return store
    }

    private fun JobManager.status(id: String = "j1") = statuses.value.getValue(id)

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

    @Test
    fun idleJobShowsTheSavedTotals() {
        val dir = dataDir()
        val store = storeWithIdleJob(dir, Progress(lastId = 40, copiedTotal = 7, runMillis = 1_000, latestId = 50))
        val s = manager(store, dir).status()

        assertEquals(40L, s.lastId)
        assertEquals(7L, s.copiedTotal)
        assertEquals(1_000L, s.runMillis)
        assertEquals(50L, s.latestId)
        assertEquals(0L, s.runningSince)
    }

    @Test
    fun stopAndStartAgainKeepCopiedTotalAndRunTime() {
        val dir = dataDir()
        val store = storeWithIdleJob(dir, Progress(copiedTotal = 7, runMillis = 1_000, latestId = 50))
        val jobs = manager(store, dir)

        val before = System.currentTimeMillis()
        assertNull(jobs.start("j1"))
        assertTrue(jobs.status().running)
        assertTrue(jobs.status().runningSince >= before, "runningSince ${jobs.status().runningSince}")
        assertEquals(7L, jobs.status().copiedTotal)
        // The clock is written while the job runs, not only when it stops.
        waitUntil("run time saved while running") { store.progress("j1").runMillis > 1_000 }
        Thread.sleep(300)
        jobs.stop("j1")
        waitUntil("job stopped") { !jobs.status().running }

        val first = store.progress("j1")
        assertTrue(first.runMillis >= 1_300, "runMillis ${first.runMillis}")
        assertEquals(7L, first.copiedTotal)
        assertEquals(50L, first.latestId)
        assertEquals(first.runMillis, jobs.status().runMillis)
        assertEquals(0L, jobs.status().runningSince)
        assertEquals(7L, jobs.status().copiedTotal)

        // Started again, the clock goes on from the same total.
        assertNull(jobs.start("j1"))
        assertEquals(first.runMillis, jobs.status().runMillis)
        assertEquals(7L, jobs.status().copiedTotal)
        Thread.sleep(200)
        jobs.stop("j1")
        waitUntil("job stopped again") { !jobs.status().running }
        val second = store.progress("j1")
        assertTrue(second.runMillis >= first.runMillis + 200, "runMillis ${second.runMillis} after ${first.runMillis}")
        assertEquals(7L, second.copiedTotal)
    }

    @Test
    fun pausedTimeIsNotCounted() {
        val dir = dataDir()
        val store = storeWithIdleJob(dir)
        val jobs = manager(store, dir)

        assertNull(jobs.start("j1"))
        Thread.sleep(100)
        jobs.pause("j1")
        assertEquals(0L, jobs.status().runningSince)
        val atPause = store.progress("j1").runMillis
        assertTrue(atPause >= 100, "runMillis $atPause")
        assertEquals(atPause, jobs.status().runMillis)
        // Paused far longer than it runs after the resume, so a slow machine stays under the bound below.
        Thread.sleep(2_000)
        assertEquals(atPause, store.progress("j1").runMillis)

        val beforeResume = System.currentTimeMillis()
        jobs.resume("j1")
        assertTrue(jobs.status().runningSince >= beforeResume)
        Thread.sleep(100)
        jobs.stop("j1")
        waitUntil("job stopped") { !jobs.status().running }
        val total = store.progress("j1").runMillis
        // Counted, the 2 s paused would put it at atPause + 2100 or more.
        assertTrue(total >= atPause + 100 && total < atPause + 1_500, "runMillis $total after $atPause")
    }

    /** A manager whose coroutines all run on one thread that the test can hold. */
    private class OneThread {
        val exec = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + exec.asCoroutineDispatcher())
        private var gate: CountDownLatch? = null

        /** Waits until everything queued on the thread so far has run. */
        fun drain() = exec.submit {}.get()
        fun hold() { gate = CountDownLatch(1).also { g -> exec.submit { g.await() } } }
        fun release() { gate?.countDown(); drain() }
        fun close() { scope.cancel(); exec.shutdownNow() }
    }

    @Test
    fun aStopAndAQuickStartKeepTheNewRun() {
        val dir = dataDir()
        val store = storeWithIdleJob(dir)
        val t = OneThread()
        try {
            val log = LogBus(dir)
            val jobs = JobManager(store, AccountManager(dir, log), log, t.scope, runTick = 50) { error("unused") }
            assertNull(jobs.start("j1"))
            t.drain() // the run waits for its accounts
            // The old run's finally runs only after the new run started, as when it is slow.
            t.hold()
            jobs.stop("j1")
            assertNull(jobs.start("j1"))
            t.release()
            t.drain()

            assertTrue(jobs.isRunning("j1"), "the new run is still known")
            assertTrue(jobs.status().running)
            assertTrue(jobs.status().runningSince > 0, "the new run's clock goes on")
            val r0 = store.progress("j1").runMillis
            waitUntil("the new run's clock counts") { store.progress("j1").runMillis > r0 }

            jobs.stop("j1")
            waitUntil("the new run stopped") { !jobs.isRunning("j1") && !jobs.status().running }
            assertEquals(0L, jobs.status().runningSince)
        } finally {
            t.close()
        }
    }

    @Test
    fun aRunCancelledBeforeItWasDispatchedLeavesNoClock() {
        val dir = dataDir()
        val store = storeWithIdleJob(dir)
        val t = OneThread()
        try {
            val log = LogBus(dir)
            val jobs = JobManager(store, AccountManager(dir, log), log, t.scope, runTick = 50) { error("unused") }
            t.drain()
            t.hold()
            assertNull(jobs.start("j1"))
            jobs.stop("j1")
            t.release()
            t.drain()

            waitUntil("the cancelled run is shown stopped") { !jobs.status().running }
            assertFalse(jobs.isRunning("j1"))
            assertEquals(0L, jobs.status().runningSince)
            val saved = store.progress("j1").runMillis
            Thread.sleep(200)
            assertEquals(saved, store.progress("j1").runMillis, "no clock writes the run time on")
        } finally {
            t.close()
        }
    }

    @Test
    fun reporterCountsCopiesAndSavesTheLatestId() {
        val dir = dataDir()
        val store = storeWithIdleJob(dir, Progress(copiedTotal = 7))
        val jobs = manager(store, dir)

        val r = jobs.reporter("j1")
        r.copied()
        r.copied()
        r.latest(500)

        assertEquals(2, jobs.status().copied)
        assertEquals(9L, jobs.status().copiedTotal)
        assertEquals(500L, jobs.status().latestId)
        assertEquals(500L, store.progress("j1").latestId)
        // Saved, so a stopped job and the next launch show it too.
        assertEquals(500L, manager(store, dir).status().latestId)
    }

    @Test
    fun aPositionPastTheLatestIdRaisesIt() {
        val dir = dataDir()
        val store = storeWithIdleJob(dir, Progress(latestId = 100))
        val jobs = manager(store, dir)

        val r = jobs.reporter("j1")
        r.position(90)
        assertEquals(100L, jobs.status().latestId)
        // The ordered loops read the latest id only once they caught up; a copied message past it is newer.
        r.position(120)
        assertEquals(120L, jobs.status().latestId)
        assertEquals(120L, store.progress("j1").latestId)
        r.position(110)
        assertEquals(120L, jobs.status().latestId)
    }

    @Test
    fun aDeletedJobsLatestIdIsNotSaved() {
        val dir = dataDir()
        val store = storeWithIdleJob(dir, Progress(latestId = 50))
        val jobs = manager(store, dir)
        val r = jobs.reporter("j1")

        // As the desktop deletes a job: the config first, then forget.
        store.updateConfig { it.copy(jobs = emptyList()) }
        jobs.forget("j1")
        // A refreshLatest that read the source before the delete saves its result after it.
        r.latest(500)

        assertEquals(Progress(), store.progress("j1"), "no orphan progress")
        assertFalse("j1" in jobs.statuses.value, "no status for the deleted job")
    }

    @Test
    fun aRefreshKeepsTheHigherIdAndSavesOnlyForTheSourceItRead() {
        val dir = dataDir()
        val store = storeWithIdleJob(dir, Progress(latestId = 100))
        val jobs = manager(store, dir)

        // A refresh that read an older value than the one a run reported since keeps the newer one.
        assertEquals(100L, jobs.saveRefreshed("j1", "src", 50))
        assertEquals(100L, store.progress("j1").latestId)
        assertEquals(150L, jobs.saveRefreshed("j1", "src", 150))
        assertEquals(150L, store.progress("j1").latestId)
        assertEquals(150L, jobs.status().latestId)

        // The job's source changed while the refresh read the old one: its result is not this source's.
        store.updateConfig { c -> c.copy(jobs = c.jobs.map { it.copy(source = "other") }) }
        assertNull(jobs.saveRefreshed("j1", "src", 900))
        assertEquals(150L, store.progress("j1").latestId)
        assertEquals(150L, jobs.status().latestId)
    }

    @Test
    fun onlyARunningJobRefreshesItsLatestIdPeriodically() {
        val dir = dataDir()
        val store = storeWithIdleJob(dir)
        val jobs = manager(store, dir, latestTick = 50)
        val refreshes = AtomicInteger()
        jobs.periodicRefresh = { id -> assertEquals("j1", id); refreshes.incrementAndGet() }

        Thread.sleep(300)
        assertEquals(0, refreshes.get(), "a stopped job is not refreshed")

        assertNull(jobs.start("j1"))
        waitUntil("the running job is refreshed again and again") { refreshes.get() >= 2 }

        jobs.stop("j1")
        waitUntil("job stopped") { !jobs.status().running }
        val atStop = refreshes.get()
        Thread.sleep(300)
        assertEquals(atStop, refreshes.get(), "the refresh ends with the run")
    }

    @Test
    fun resetProgressClearsTheRunTotals() {
        val dir = dataDir()
        val store = storeWithIdleJob(dir, Progress(lastId = 40, copiedTotal = 7, runMillis = 1_000, latestId = 50))
        val jobs = manager(store, dir)

        jobs.resetProgress("j1")

        val p = store.progress("j1")
        assertEquals(listOf(0L, 0L, 0L, 0L), listOf(p.lastId, p.copiedTotal, p.runMillis, p.latestId))
        val s = jobs.status()
        assertEquals(listOf(0L, 0L, 0L, 0L), listOf(s.lastId, s.copiedTotal, s.runMillis, s.latestId))
    }

    @Test
    fun refreshLatestWithoutALoggedInAccountDoesNothing() {
        val dir = dataDir()
        val store = storeWithIdleJob(dir, Progress(latestId = 50))
        store.updateConfig { it.copy(jobs = it.jobs + Job(id = "noacc", source = "src", target = "dst")) }
        val jobs = manager(store, dir)

        runBlocking {
            assertNull(jobs.refreshLatest("j1"))
            assertNull(jobs.refreshLatest("noacc"))
            assertNull(jobs.refreshLatest("missing"))
        }

        assertEquals(50L, store.progress("j1").latestId)
        assertEquals(50L, jobs.status().latestId)
        assertFalse(jobs.isRunning("j1"))
    }
}

package org.tgbackup.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.tgbackup.app.data.Store
import org.tgbackup.app.engine.JobManager
import org.tgbackup.app.engine.LogBus
import org.tgbackup.app.export.ExportManager
import org.tgbackup.app.tg.AccountManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * Hosts the engine on the desktop: the replacement for the Android `App` and `BackupService`.
 *
 * [close] behaves like Android process death, not like "stop all": it never calls `jobs.stopAll()`
 * (that clears `autoResume`), so interrupted jobs come back on the next [start].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopApp(
    dataDir: Path,
    power: PowerRequests = PowerRequests.forCurrentOs(),
) : AutoCloseable {

    private val dir = Files.createDirectories(dataDir).toFile()
    private val sleepGuard = SleepGuard(power)
    private val closed = AtomicBoolean(false)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val log = LogBus(dir)
    val store = Store(dir)
    val accounts = AccountManager(dir, log)
    val exports = ExportManager(dir, accounts, log)
    val jobs = JobManager(store, accounts, log, scope) { exports }

    /** Mirrors `App.onCreate()` (without Sentry and the foreground service) plus the `jobs.restore()` call. */
    fun start() {
        if (store.migratedFromLegacy) {
            log.info("ההגדרות יובאו מהגרסה הקודמת. יש להתחבר מחדש לכל חשבון (פעם אחת).")
        }

        // Keep one TDLib client per configured account.
        scope.launch {
            store.config.map { it.accounts }.distinctUntilChanged().collect { accounts.sync(it) }
        }

        // Keep the PC awake while a job or an export runs. `exports.running` keeps a finished export until it
        // is dismissed, so also re-emit when its job completes. Power calls can block (up to 5 s) or throw:
        // they run on IO and never break the collector.
        val exportActive = exports.running.flatMapLatest { running ->
            if (running == null) {
                flowOf(false)
            } else {
                flow {
                    emit(running.job.isActive)
                    running.job.join()
                    emit(false)
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            combine(jobs.anyRunning, exportActive) { r, e -> r || e }
                .distinctUntilChanged()
                .collect { active -> updateSleepGuard(active) }
        }

        // What MainActivity and BackupService did on launch: bring interrupted jobs back.
        jobs.restore()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { exports.stop() }.onFailure { log.warn("עצירת הייצוא נכשלה: ${it.message}") }
        runBlocking {
            // Process-death equivalent: progress and autoResume stay on disk; nothing writes after this.
            if (withTimeoutOrNull(10.seconds) { scope.coroutineContext.job.cancelAndJoin() } == null) {
                log.warn("חלק מהמשימות לא נעצרו תוך 10 שניות")
            }
            withTimeoutOrNull(5.seconds) { exports.running.value?.job?.join() }
            val clients = accounts.clients.value.values
            val done = withTimeoutOrNull(15.seconds) {
                clients.map { c ->
                    async(Dispatchers.IO) {
                        runCatching { c.close() }.onFailure { log.warn("סגירת חשבון נכשלה: ${it.message}") }
                    }
                }.awaitAll()
            }
            if (done == null) log.warn("סגירת החשבונות לא הסתיימה תוך 15 שניות")
        }
        updateSleepGuard(false)
    }

    private fun updateSleepGuard(active: Boolean) {
        runCatching { sleepGuard.update(active) }
            .onFailure { log.warn("בקשת מניעת שינה נכשלה: ${it.message}") }
    }
}

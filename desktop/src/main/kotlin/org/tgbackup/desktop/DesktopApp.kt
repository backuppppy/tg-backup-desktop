package org.tgbackup.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
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
import org.tgbackup.app.tg.AuthUi
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
    private val autostart: Autostart = Autostart.forCurrentOs(),
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
    /** Start with Windows and keep awake (Settings). */
    val prefs = DesktopPrefs(dir)

    /**
     * An export is active until its job completes: `exports.running` keeps a finished export until it is dismissed, so this follows the
     * export job's completion (activeWhileRunning).
     */
    private val exportActive: Flow<Boolean> = exports.running.flatMapLatest { running ->
        if (running == null) {
            flowOf(false)
        } else {
            activeWhileRunning(running.job)
        }
    }

    /** A job or an export runs: the sleep guard's signal (without the Settings switch), and what closing the window asks about. */
    private val busy: StateFlow<Boolean> = busySignal(jobs.anyRunning, exportActive).stateIn(scope, SharingStarted.Eagerly, false)

    fun isBusy(): Boolean = busy.value

    /** Mirrors `App.onCreate()` (without Sentry and the foreground service) plus the `jobs.restore()` call. */
    fun start() {
        if (store.migratedFromLegacy) {
            log.info("ההגדרות יובאו מהגרסה הקודמת. יש להתחבר מחדש לכל חשבון (פעם אחת).")
        }

        // Keep one TDLib client per configured account.
        scope.launch {
            store.config.map { it.accounts }.distinctUntilChanged().collect { accounts.sync(it) }
        }

        // Keep the PC awake while work runs ([busy]), unless switched off in Settings. Power calls can block (up to 5 s) or throw: they run
        // on IO and never break the collector.
        scope.launch(Dispatchers.IO) {
            sleepSignal(busy, prefs.value.map { it.keepAwake })
                .collect { active -> updateSleepGuard(active) }
        }

        // The Run value follows the switch, now and on every change (a moved exe is rewritten at launch).
        scope.launch(Dispatchers.IO) {
            prefs.value.map { it.autostart }.distinctUntilChanged().collect { on ->
                runCatching { autostart.apply(on) }
                    .onFailure { log.warn("עדכון ההפעלה עם Windows נכשל: ${it.message}") }
            }
        }

        // The source's latest id of every job, read without running it: whenever an account of the job logs in
        // (which is also how a saved session comes back after launch), and right away for the jobs that have none.
        scope.launch {
            accounts.clients.collectLatest { clients ->
                coroutineScope {
                    clients.forEach { (id, client) ->
                        launch {
                            client.auth.map { it is AuthUi.Ready }.distinctUntilChanged().filter { it }.collect {
                                store.config.value.jobs.filter { id in it.accountIds }.forEach { j ->
                                    launch { jobs.refreshLatest(j.id) }
                                }
                            }
                        }
                    }
                }
            }
        }
        store.config.value.jobs.filter { store.progress(it.id).latestId == 0L }.forEach { j ->
            scope.launch { jobs.refreshLatest(j.id) }
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
            exports.running.value?.job?.let { export ->
                // join() would start a still-lazy export: cancel one that has not started, then wait.
                if (!export.isActive && !export.isCompleted) export.cancel()
                withTimeoutOrNull(5.seconds) { export.join() }
            }
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

/** Whether to keep the PC awake: work runs ([busy], the one shared busy signal), and the Settings switch is on. */
internal fun sleepSignal(busy: Flow<Boolean>, keepAwake: Flow<Boolean>): Flow<Boolean> =
    combine(busy, keepAwake) { b, k -> b && k }.distinctUntilChanged()

/** [sleepSignal] straight from a job flag and an export flag. */
internal fun sleepSignal(jobs: Flow<Boolean>, export: Flow<Boolean>, keepAwake: Flow<Boolean>): Flow<Boolean> =
    sleepSignal(busySignal(jobs, export), keepAwake)

/** Work runs: a job or an export. */
internal fun busySignal(jobs: Flow<Boolean>, export: Flow<Boolean>): Flow<Boolean> =
    combine(jobs, export) { r, e -> r || e }.distinctUntilChanged()

/**
 * `true` until [job] completes, then `false`. A lazy job that has not started yet counts as active (ExportManager
 * publishes the export before `job.start()`). Completion is observed through [Job.invokeOnCompletion], never
 * through `join()`, so collecting never starts a lazy job: only ExportManager starts it.
 */
internal fun activeWhileRunning(job: Job): Flow<Boolean> = callbackFlow {
    trySend(!job.isCompleted)
    val handle = job.invokeOnCompletion {
        trySend(false)
        channel.close()
    }
    awaitClose { handle.dispose() }
}

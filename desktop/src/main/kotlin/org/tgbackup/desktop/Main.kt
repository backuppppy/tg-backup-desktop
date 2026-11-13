package org.tgbackup.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import io.sentry.Sentry
import org.tgbackup.app.BuildConfig
import org.tgbackup.desktop.server.ApiServer
import org.tgbackup.desktop.server.HEALTH_PATH
import org.tgbackup.desktop.server.RemoteServer
import org.tgbackup.desktop.server.SESSION_COOKIE
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

const val VERSION = BuildConfig.VERSION_NAME

/** How long closing the window may take (DesktopApp.close() alone can take up to ~30 s) before the process ends hard. */
private const val SHUTDOWN_LIMIT_MS = 40_000L

/**
 * TGBackup.exe: the engine, the internal server on 127.0.0.1, and the screens in a WebView2 window. Closing the
 * window ends the program, unless work runs: then it goes on by the clock, with a balloon the first time ([WindowLoop], the
 * tray); `--minimized` (start with Windows) starts in the tray. `--smoke` runs the same program on a throw-away data dir,
 * checks the API and the real screen, and exits 0 (or 1 with `PAGE_FAIL <reason>`).
 */
fun main(args: Array<String>) {
    // The uninstaller: put back Windows' "sleep after" that a running or crashed program left at "never", and end.
    if ("--restore-power" in args) restorePowerAndExit()
    // First: TDLib extracts its DLLs to java.io.tmpdir, which must be ASCII.
    NativeTmp.ensureAsciiTmpdir("org.tgbackup.desktop.MainKt", args)
    println("TGBackup $VERSION")
    val smoke = AppSmoke("--smoke" in args)
    smoke.startWatchdog()
    val code = try {
        run(smoke, startHidden = "--minimized" in args)
    } catch (t: Throwable) {
        // Before the window: the engine or the server did not start. Never leave a process without a window.
        println("START_FAIL ${t::class.java.simpleName}")
        t.printStackTrace()
        smoke.fail("${t::class.java.simpleName}: ${t.message}")
        if (Sentry.isEnabled()) Sentry.captureException(t)
        1
    }
    if (code == 0) smoke.finished() else smoke.fail("window exit code $code")
    halt(code)
}

/** Ends the process at once: first sends what Sentry still holds (when it runs), so a report is not lost. */
private fun halt(code: Int): Nothing {
    System.out.flush()
    if (Sentry.isEnabled()) runCatching { Sentry.flush(2000) }
    Runtime.getRuntime().halt(code)
    error("unreachable")
}

/** Returns the exit code ([WindowLifecycle.run]). */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private fun run(smoke: AppSmoke, startHidden: Boolean): Int {
    val dataDir = if (smoke.enabled) smokeDataDir(AppDirs.dataDir()) else AppDirs.dataDir()
    println("DATA_DIR $dataDir")
    Telemetry.init(dataDir, DesktopBuildConfig.SENTRY_DSN, smoke.enabled)
    // One program per data dir: a second launch shows the running one's window and exits.
    val instance = SingleInstance(dataDir)
    if (!instance.acquire()) {
        // Start with Windows while the program already runs: nothing to show, nothing to say.
        if (startHidden) exitQuietly("INSTANCE_MINIMIZED_EXIT")
        handOffOrOwn(instance, smoke)
    }
    // The smoke's throw-away dir is wiped only once it is ours (never under a running smoke).
    if (smoke.enabled) wipeExceptLock(dataDir)
    // `--smoke` never touches the real Run key (its throw-away prefs would switch "start with Windows" off).
    // `--smoke` never changes Windows' sleep settings (its seeded job could count as work).
    var server: ApiServer? = null
    lateinit var app: DesktopApp
    app = DesktopApp(
        dataDir, autostart = Autostart.forLaunch(smoke.enabled), sleepOverride = !smoke.enabled,
        // The phone's server shares the window server's command locks and loaded chats; it exists before app.start().
        startRemoteServer = { names ->
            val window = checkNotNull(server) { "the window's server is not created" }
            val remote = RemoteServer(app, app.remoteAuth, allowedNames = names, handles = window.handles, locks = window.locks)
            remote.start()
            AutoCloseable { remote.stop() }
        },
    )
    // From here on, the configured phone numbers are masked in anything sent to Sentry.
    Telemetry.phones = { app.store.config.value.accounts.map { it.phone } }
    val shutdown = {
        val started = System.nanoTime()
        // First: a launch during shutdown keeps retrying (and then owns the dir) instead of knocking on a closing port.
        instance.unpublish()
        Shutdown.run(listOfNotNull(app::close, server?.let { s -> s::stop }), SHUTDOWN_LIMIT_MS)
        println("SHUTDOWN_DONE ${(System.nanoTime() - started) / 1_000_000}ms")
    }
    val profileDir = dataDir.resolve("webview2")
    // `--smoke` keeps no tray: closing its window always ends it.
    val tray = if (smoke.enabled) null else Tray.createOrNull()
    lateinit var loop: WindowLoop
    loop = WindowLoop(
        window = {
            AppWindow.open(
                checkNotNull(server).entryUrl(), smoke, profileDir,
                onUp = { loop.windowUp() },
                onRejectedLink = { app.log.warn("קישור שאינו http(s) נחסם") },
            )
        },
        busy = app::isBusy,
        tray = tray,
        startHidden = startHidden,
        toFront = AppWindow::toFront,
    )
    val code = WindowLifecycle.run(
        open = {
            // A second launch: the window opens again (from the tray) or comes to the front. Created before
            // app.start(): the phone access may start the phone's server, which takes this one's locks.
            val s = ApiServer(app, onShow = loop::show).also { server = it }
            app.start()
            instance.publish(s.start(), s.instanceToken)
            if (smoke.enabled) SmokeApi(s).checkAndSeed()
            loop.run()
        },
        shutdown = shutdown,
        tellNoWebView = { if (!smoke.enabled) showError(MSG_NO_WEBVIEW) },
    )
    instance.release()
    if (smoke.enabled) cleanSmokeDir(dataDir, profileDir)
    return code
}

/**
 * Another instance owns the data dir: asks it to show its window and exits 0 (`INSTANCE_HANDED_OFF`). When it
 * does not answer within the retries (it hangs), says so in a box (not under `--smoke`) and exits 0 too. When it
 * ended during the retries, this launch owns the data dir and returns to start normally.
 */
private fun handOffOrOwn(instance: SingleInstance, smoke: AppSmoke) {
    // This launch is the one the user just started: it lets the running one bring its window to the front.
    runCatching { User32More.INSTANCE.AllowSetForegroundWindow(ASFW_ANY) }
    when (instance.handOff()) {
        HandOff.Acquired -> {
            println("INSTANCE_ACQUIRED_AFTER_WAIT")
            return
        }
        HandOff.Shown -> println("INSTANCE_HANDED_OFF")
        HandOff.NoAnswer -> {
            println("INSTANCE_NO_ANSWER")
            System.out.flush()
            if (!smoke.enabled) showMessage(MSG_ALREADY_RUNNING, MB_ICONINFORMATION)
        }
    }
    exitQuietly(null)
}

/**
 * `--restore-power` (run by the uninstaller): [SleepOverride.recover] on the real data dir, then exit 0 whatever
 * happened (an uninstall never stops on it). Prints `POWER_RESTORED yes|no` or `POWER_RESTORE_FAIL <reason>`.
 */
private fun restorePowerAndExit(): Nothing {
    val restored = runCatching {
        PowerRequests.forCurrentOs(AppDirs.dataDir().toFile(), log = ::println).recover()
    }
    restored.onSuccess { println("POWER_RESTORED ${if (it) "yes" else "no"}") }
        .onFailure { println("POWER_RESTORE_FAIL ${it::class.java.simpleName}: ${it.message}") }
    exitQuietly(null)
}

/** Ends this launch with 0 at once (before anything of ours started). */
private fun exitQuietly(line: String?): Nothing {
    line?.let(::println)
    halt(0)
}

/** Everything in [dataDir] except the lock this process holds (Windows cannot delete a locked file). */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private fun wipeExceptLock(dataDir: Path) {
    dataDir.listDirectoryEntries().filter { it.fileName.toString() != SingleInstance.LOCK_NAME }
        .forEach { it.deleteRecursively() }
}

/** A blocking Windows error box (the window could not be shown, so this is the only way to tell the user). */
private fun showError(text: String) = showMessage(text, MB_ICONERROR)

private fun showMessage(text: String, icon: Int) {
    // Hebrew text: right-to-left reading order, right-aligned.
    Native.load("user32", User32Box::class.java)
        .MessageBoxW(null, WString(text), WString(WINDOW_TITLE), MB_OK or icon or MB_RTLREADING or MB_RIGHT)
}

private interface User32Box : Library {
    fun MessageBoxW(hwnd: Pointer?, text: WString, caption: WString, type: Int): Int
}

private const val MB_OK = 0x0
private const val ASFW_ANY = -1
private const val MB_ICONERROR = 0x10
private const val MB_ICONINFORMATION = 0x40
private const val MB_RIGHT = 0x80000
private const val MB_RTLREADING = 0x100000

private const val MSG_ALREADY_RUNNING = "התוכנה כבר פועלת."

/**
 * `--smoke` only: reports that WebView2 used the smoke profile, then removes the throw-away data dir. WebView2's
 * helper processes can hold files for a moment after the window is gone, so it retries for a few seconds.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private fun cleanSmokeDir(dataDir: Path, profileDir: Path) {
    val entries = profileDir.toFile().list()?.size ?: 0
    println("SMOKE_PROFILE entries=$entries")
    var cleaned = false
    for (attempt in 1..10) {
        cleaned = runCatching { dataDir.deleteRecursively() }.isSuccess && !dataDir.exists()
        if (cleaned) break
        Thread.sleep(500)
    }
    println("SMOKE_CLEANED ${if (cleaned) "yes" else "partial"}")
}

/**
 * `--smoke` only, against the throw-away data dir: `GET /api/health` (prints `API_OK`), then one dummy account
 * (apiId 1, it never logs in) and one job through the real API, so the Jobs screen shows a card with its buttons;
 * then the phone access is switched on ([SmokeRemote]: `REMOTE_STATE <state>`, `SMOKE_PID <pid>`).
 */
private class SmokeApi(private val server: ApiServer) {
    private val base: String = URI(server.entryUrl()).let { "${it.scheme}://${it.authority}" }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun checkAndSeed() {
        val health = send(HttpRequest.newBuilder(URI("$base$HEALTH_PATH")).GET(), auth = false)
        check(health.statusCode() == 200 && health.body() == "ok") { "health answered ${health.statusCode()}" }
        println("API_OK")
        post(
            "/api/accounts",
            """{"name":"smoke","apiId":1,"apiHash":"${"0".repeat(32)}","phone":"+10000000000"}""",
        )
        val state = send(HttpRequest.newBuilder(URI("$base/api/state")).GET(), auth = true)
        check(state.statusCode() == 200) { "state answered ${state.statusCode()}" }
        val accountId = Json.parseToJsonElement(state.body()).jsonObject["accounts"]!!.jsonArray
            .first().jsonObject["id"]!!.jsonPrimitive.content
        post(
            "/api/jobs",
            """{"name":"smoke","source":"https://t.me/c/1000000000/1","target":"https://t.me/c/2000000000/1",""" +
                """"accountIds":["$accountId"]}""",
        )
        println("SMOKE_SEEDED account+job")
        // The phone access on, through the real API: a password, the switch, then the first state other than Off.
        put("/api/remote/password", """{"password":"smoke-password"}""")
        put("/api/remote/enabled", """{"enabled":true}""")
        val remote = SmokeRemote.awaitState({
            val r = send(HttpRequest.newBuilder(URI("$base/api/remote")).GET(), auth = true)
            check(r.statusCode() == 200) { "GET /api/remote answered ${r.statusCode()}" }
            Json.parseToJsonElement(r.body()).jsonObject["state"]!!.jsonPrimitive.content
        })
        println(SmokeRemote.line(remote))
        // The process that serves (after the NativeTmp restart): CI lists its listening sockets.
        println("SMOKE_PID ${ProcessHandle.current().pid()}")
    }

    private fun post(path: String, json: String) = write("POST", path, json)

    private fun put(path: String, json: String) = write("PUT", path, json)

    private fun write(method: String, path: String, json: String) {
        val r = send(
            HttpRequest.newBuilder(URI("$base$path")).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json)),
            auth = true,
        )
        check(r.statusCode() == 200) { "$method $path answered ${r.statusCode()}" }
    }

    private fun send(b: HttpRequest.Builder, auth: Boolean): HttpResponse<String> {
        if (auth) b.header("Cookie", "$SESSION_COOKIE=${server.sessionToken}")
        return http.send(b.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
    }
}

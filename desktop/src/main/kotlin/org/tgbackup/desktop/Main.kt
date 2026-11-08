package org.tgbackup.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import org.tgbackup.app.BuildConfig
import org.tgbackup.desktop.server.ApiServer
import org.tgbackup.desktop.server.HEALTH_PATH
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
 * window ends the program, unless work runs and the user chooses to go on in the background ([WindowLoop], the
 * tray); `--minimized` (start with Windows) starts in the tray. `--smoke` runs the same program on a throw-away data dir,
 * checks the API and the real screen, and exits 0 (or 1 with `PAGE_FAIL <reason>`).
 */
fun main(args: Array<String>) {
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
        1
    }
    if (code == 0) smoke.finished() else smoke.fail("window exit code $code")
    System.out.flush()
    Runtime.getRuntime().halt(code)
}

/** Returns the exit code ([WindowLifecycle.run]). */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private fun run(smoke: AppSmoke, startHidden: Boolean): Int {
    val dataDir = if (smoke.enabled) smokeDataDir(AppDirs.dataDir()) else AppDirs.dataDir()
    println("DATA_DIR $dataDir")
    // One program per data dir: a second launch shows the running one's window and exits.
    val instance = SingleInstance(dataDir)
    if (!instance.acquire()) handOffOrOwn(instance, smoke)
    // The smoke's throw-away dir is wiped only once it is ours (never under a running smoke).
    if (smoke.enabled) wipeExceptLock(dataDir)
    val app = DesktopApp(dataDir)
    var server: ApiServer? = null
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
    val loop = WindowLoop(
        window = {
            AppWindow.open(checkNotNull(server).entryUrl(), smoke, profileDir) {
                app.log.warn("קישור שאינו http(s) נחסם")
            }
        },
        busy = app::isBusy,
        askBackground = ::askBackground,
        tray = tray,
        startHidden = startHidden,
        toFront = AppWindow::toFront,
    )
    val code = WindowLifecycle.run(
        open = {
            app.start()
            // A second launch: the window opens again (from the tray) or comes to the front.
            val s = ApiServer(app, onShow = loop::show).also { server = it }
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
    System.out.flush()
    Runtime.getRuntime().halt(0)
}

/** Everything in [dataDir] except the lock this process holds (Windows cannot delete a locked file). */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private fun wipeExceptLock(dataDir: Path) {
    dataDir.listDirectoryEntries().filter { it.fileName.toString() != SingleInstance.LOCK_NAME }
        .forEach { it.deleteRecursively() }
}

/** A blocking Windows error box (the window could not be shown, so this is the only way to tell the user). */
private fun showError(text: String) = showMessage(text, MB_ICONERROR)

private fun showMessage(text: String, icon: Int, buttons: Int = MB_OK): Int =
    // Hebrew text: right-to-left reading order, right-aligned.
    Native.load("user32", User32Box::class.java)
        .MessageBoxW(null, WString(text), WString(WINDOW_TITLE), buttons or icon or MB_RTLREADING or MB_RIGHT)

/** The window was closed while work runs: true (Yes) → on in the background, false (No) → the program ends. */
private fun askBackground(): Boolean =
    // MB_SETFOREGROUND: the window is gone, the box must not open behind another program.
    showMessage(MSG_GO_ON_IN_BACKGROUND, MB_ICONQUESTION or MB_SETFOREGROUND, MB_YESNO) == IDYES

private interface User32Box : Library {
    fun MessageBoxW(hwnd: Pointer?, text: WString, caption: WString, type: Int): Int
}

private const val MB_OK = 0x0
private const val MB_YESNO = 0x4
private const val MB_ICONQUESTION = 0x20
private const val MB_SETFOREGROUND = 0x10000
private const val IDYES = 6
private const val ASFW_ANY = -1
private const val MB_ICONERROR = 0x10
private const val MB_ICONINFORMATION = 0x40
private const val MB_RIGHT = 0x80000
private const val MB_RTLREADING = 0x100000

private const val MSG_ALREADY_RUNNING = "התוכנה כבר פועלת."
private const val MSG_GO_ON_IN_BACKGROUND = "יש משימה או ייצוא שרצים. להמשיך לרוץ ברקע?\nכן – ממשיכה ליד השעון.\nלא – התוכנה תיסגר, והמשימות ימשיכו מאותו מקום בפתיחה הבאה."

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
 * (apiId 1, it never logs in) and one job through the real API, so the Jobs screen shows a card with its buttons.
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
    }

    private fun post(path: String, json: String) {
        val r = send(
            HttpRequest.newBuilder(URI("$base$path")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)),
            auth = true,
        )
        check(r.statusCode() == 200) { "POST $path answered ${r.statusCode()}" }
    }

    private fun send(b: HttpRequest.Builder, auth: Boolean): HttpResponse<String> {
        if (auth) b.header("Cookie", "$SESSION_COOKIE=${server.sessionToken}")
        return http.send(b.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
    }
}

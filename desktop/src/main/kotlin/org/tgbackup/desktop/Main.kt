package org.tgbackup.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

const val VERSION = BuildConfig.VERSION_NAME

/** How long closing the window may take (DesktopApp.close() alone can take up to ~30 s) before the process ends hard. */
private const val SHUTDOWN_LIMIT_MS = 40_000L

/**
 * TGBackup.exe: the engine, the internal server on 127.0.0.1, and the screens in a WebView2 window. Closing the
 * window ends the program (the tray comes in plan 5). `--smoke` runs the same program on a throw-away data dir,
 * checks the API and the real screen, and exits 0 (or 1 with `PAGE_FAIL <reason>`).
 */
fun main(args: Array<String>) {
    // First: TDLib extracts its DLLs to java.io.tmpdir, which must be ASCII.
    NativeTmp.ensureAsciiTmpdir("org.tgbackup.desktop.MainKt", args)
    println("TGBackup $VERSION")
    val smoke = AppSmoke("--smoke" in args)
    smoke.startWatchdog()
    try {
        run(smoke)
    } catch (t: Throwable) {
        if (!smoke.enabled) throw t
        smoke.fail("${t::class.java.simpleName}: ${t.message}")
    }
    smoke.finished()
    System.out.flush()
    Runtime.getRuntime().halt(0)
}

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private fun run(smoke: AppSmoke) {
    val dataDir = if (smoke.enabled) {
        smokeDataDir(AppDirs.dataDir()).also { if (it.exists()) it.deleteRecursively() }
    } else {
        AppDirs.dataDir()
    }
    println("DATA_DIR $dataDir")
    val app = DesktopApp(dataDir)
    app.start()
    val server = ApiServer(app)
    server.start()
    if (smoke.enabled) SmokeApi(server).checkAndSeed()

    AppWindow.open(server.entryUrl(), smoke, dataDir.resolve("webview2")) {
        app.log.warn("קישור שאינו http(s) נחסם")
    }

    val started = System.nanoTime()
    Shutdown.run(listOf(app::close, server::stop), SHUTDOWN_LIMIT_MS)
    println("SHUTDOWN_DONE ${(System.nanoTime() - started) / 1_000_000}ms")
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

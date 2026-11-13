package org.tgbackup.desktop

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import org.tgbackup.app.data.Account
import org.tgbackup.app.data.Job
import org.tgbackup.desktop.remote.ServeConfig
import org.tgbackup.desktop.remote.TailscaleCli
import org.tgbackup.desktop.remote.TsStatus
import org.tgbackup.desktop.server.ApiServer
import org.tgbackup.desktop.server.Events
import org.tgbackup.desktop.server.MSG_BAD_REQUEST
import org.tgbackup.desktop.server.Refusal
import org.tgbackup.desktop.server.RemoteServer
import org.tgbackup.desktop.server.RunningExportDto
import org.tgbackup.desktop.server.TgbJson
import org.tgbackup.desktop.server.command
import java.nio.file.Files
import java.nio.file.Path

/**
 * The real engine and server without the window, for UI tests and for working on the screens in a browser.
 * Env: `TGB_DEV_DATA` (data dir, default a fresh temp dir), `TGB_DEV_PORT` (default 8765),
 * `TGB_DEV_FIXTURE` (any value turns on the test-only routes of [devExtraRoutes]; `long-names` also seeds accounts
 * and jobs with long names), `TGB_DEV_REMOTE_PORT` (starts the phone's [RemoteServer] on that port too; the tests use
 * 8766, never the program's 47800) with `TGB_DEV_REMOTE_PASSWORD` (sets its password). Prints `DEV_REMOTE_URL <url>`
 * (with a remote port) and then `DEV_URL <url>`, runs until killed.
 */
fun main(args: Array<String>) {
    NativeTmp.ensureAsciiTmpdir("org.tgbackup.desktop.DevServerKt", args)
    val dataDir = System.getenv("TGB_DEV_DATA")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?: Files.createTempDirectory("tgb-dev")
    val port = System.getenv("TGB_DEV_PORT")?.toIntOrNull() ?: 8765

    val fixture = System.getenv("TGB_DEV_FIXTURE")
    // Like the smoke: a dev server never changes Windows' sleep settings. Tailscale is never there: the phone
    // access shows NotInstalled when switched on.
    val app = DesktopApp(dataDir, sleepOverride = false, tailscale = NoTailscale)
    if (fixture == "long-names") seedLongNames(app)
    app.start()
    val server = ApiServer(app, "127.0.0.1", port, devExtraRoutes(fixture))
    server.start()
    val remotePort = System.getenv("TGB_DEV_REMOTE_PORT")?.toIntOrNull()
    val remote = remotePort?.let { p ->
        // Through the controller, so the status (hasPassword) sees it.
        System.getenv("TGB_DEV_REMOTE_PASSWORD")?.takeIf { it.isNotEmpty() }?.let { app.remote.setPassword(it) }
        RemoteServer(app, app.remoteAuth, p, allowedNames = { emptySet() }, handles = server.handles, locks = server.locks)
            .also { it.start() }
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        remote?.stop()
        server.stop()
        app.close()
    })
    println("DEV_DATA $dataDir")
    if (remotePort != null) println("DEV_REMOTE_URL http://127.0.0.1:$remotePort/")
    println("DEV_URL ${server.entryUrl()}")
    System.out.flush()
    Thread.currentThread().join()
}

/** The DevServer's Tailscale: never installed (the phone's server, when wanted, is started by TGB_DEV_REMOTE_PORT). */
private object NoTailscale : TailscaleCli {
    override fun status(): TsStatus = TsStatus.NotInstalled
    override fun serveStatus(dnsName: String?): ServeConfig = ServeConfig.None
    override fun serveOn(port: Int) = Unit
    override fun serveOff() = Unit
}

/**
 * The DevServer's test-only routes, only when [fixture] (TGB_DEV_FIXTURE) is set; [ApiServer] never has them.
 * - `POST /api/dev/export-state` with a `RunningExportDto` (or `null`) publishes it as the `export` event, as if
 *   ExportManager had, so the UI tests can show every progress panel state. The engine is not touched.
 */
internal fun devExtraRoutes(fixture: String?): Route.(Events) -> Unit =
    if (fixture.isNullOrBlank()) {
        {}
    } else {
        { events -> devRoutes(events) }
    }

private val RunningExportOrNull = RunningExportDto.serializer().nullable

private fun Route.devRoutes(events: Events) {
    post("/api/dev/export-state") {
        command {
            val value = try {
                TgbJson.decodeFromString(RunningExportOrNull, call.receiveText())
            } catch (e: SerializationException) {
                throw Refusal(HttpStatusCode.BadRequest, MSG_BAD_REQUEST)
            } catch (e: IllegalArgumentException) {
                throw Refusal(HttpStatusCode.BadRequest, MSG_BAD_REQUEST)
            }
            events.publish("export", TgbJson.encodeToJsonElement(RunningExportOrNull, value))
        }
    }
}

/**
 * `TGB_DEV_FIXTURE=long-names` (Task 10): accounts, jobs and log lines long enough to test wrapping at 390 px.
 * A job name of exactly 120 characters (Hebrew and English, with an `\u200E-100…` id inside), ids as the program
 * shows them (`\u200E-100…`), a long account name, and log lines with and without break opportunities.
 * Dummy accounts: they never log in.
 */
internal fun seedLongNames(app: DesktopApp) {
    val long = "חשבון גיבוי עם שם ארוך מאוד מאוד שלא נכנס בשורה אחת Very Long English Account Name"
    val accounts = listOf(
        Account(id = "long1", name = long, apiId = 1, apiHash = "0".repeat(32), phone = "+10000000000"),
        Account(id = "long2", name = "Second account with a really long English name for wrapping", apiId = 1,
            apiHash = "0".repeat(32), phone = "+10000000001"),
    )
    val name120 = "משימת העתקה מהערוץ הראשי \u200E-1001234567890 אל ארכיון הגיבוי Archive Backup Mirror " +
        "עם שם ארוך במיוחד שלא נכנס בשורה אחת, OK"
    check(name120.length == 120) { "long-names: the job name is ${name120.length} characters" }
    val jobs = listOf(
        Job(id = "longjob1", name = name120,
            source = "\u200E-1001234567890", target = "\u200E-1009876543210", accountIds = listOf("long1")),
        Job(id = "longjob2", name = "Copy job with a very long English name that should wrap nicely in the card",
            source = "https://t.me/c/1000000000/1", target = "https://t.me/c/2000000000/1", accountIds = listOf("long1", "long2")),
    )
    app.store.updateConfig { it.copy(accounts = accounts, jobs = jobs) }
    // Run totals for the job cards: 2:14:05 of run time and a known latest id; the second job's latest id is not
    // known yet and its numbers need thousands separators.
    app.store.saveProgress("longjob1") { it.copy(lastId = 45_210, copiedTotal = 12_340, runMillis = 8_045_000, latestId = 50_000) }
    app.store.saveProgress("longjob2") { it.copy(lastId = 987_654, copiedTotal = 1_234_567, runMillis = 90_061_000) }
    jobs.forEach { app.jobs.showSavedProgress(it.id) }
    app.log.info("[$name120] הועתקו 1500 הודעות מתוך \u200E-1001234567890 אל \u200E-1009876543210 — Copied 1500 messages, last id 987654")
    app.log.warn("[$long] FLOOD_WAIT_120 בהעתקה מהערוץ \u200E-1001234567890, ממתין 120 שניות לפני הניסיון הבא — waiting before the next try")
    app.log.error("C:\\Users\\משתמש\\AppData\\Local\\TGBackup\\exports\\" + "Very_Long_Folder_Name_Without_Any_Spaces_".repeat(3) + "file.json")
}

package org.tgbackup.desktop

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import org.tgbackup.app.data.Account
import org.tgbackup.app.data.Job
import org.tgbackup.desktop.server.ApiServer
import org.tgbackup.desktop.server.Events
import org.tgbackup.desktop.server.MSG_BAD_REQUEST
import org.tgbackup.desktop.server.Refusal
import org.tgbackup.desktop.server.RunningExportDto
import org.tgbackup.desktop.server.TgbJson
import org.tgbackup.desktop.server.command
import java.nio.file.Files
import java.nio.file.Path

/**
 * The real engine and server without the window, for UI tests and for working on the screens in a browser.
 * Env: `TGB_DEV_DATA` (data dir, default a fresh temp dir), `TGB_DEV_PORT` (default 8765),
 * `TGB_DEV_FIXTURE` (any value turns on the test-only routes of [devExtraRoutes]; `long-names` also seeds accounts
 * and jobs with long names). Prints `DEV_URL <url>`, runs until killed.
 */
fun main(args: Array<String>) {
    NativeTmp.ensureAsciiTmpdir("org.tgbackup.desktop.DevServerKt", args)
    val dataDir = System.getenv("TGB_DEV_DATA")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?: Files.createTempDirectory("tgb-dev")
    val port = System.getenv("TGB_DEV_PORT")?.toIntOrNull() ?: 8765

    val fixture = System.getenv("TGB_DEV_FIXTURE")
    val app = DesktopApp(dataDir)
    if (fixture == "long-names") seedLongNames(app)
    app.start()
    val server = ApiServer(app, "127.0.0.1", port, devExtraRoutes(fixture))
    server.start()
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop()
        app.close()
    })
    println("DEV_DATA $dataDir")
    println("DEV_URL ${server.entryUrl()}")
    System.out.flush()
    Thread.currentThread().join()
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
    app.log.info("[$name120] הועתקו 1500 הודעות מתוך \u200E-1001234567890 אל \u200E-1009876543210 — Copied 1500 messages, last id 987654")
    app.log.warn("[$long] FLOOD_WAIT_120 בהעתקה מהערוץ \u200E-1001234567890, ממתין 120 שניות לפני הניסיון הבא — waiting before the next try")
    app.log.error("C:\\Users\\משתמש\\AppData\\Local\\TGBackup\\exports\\" + "Very_Long_Folder_Name_Without_Any_Spaces_".repeat(3) + "file.json")
}

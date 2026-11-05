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

/** Accounts and jobs whose names are long enough to test wrapping (dummy accounts: they never log in). */
private fun seedLongNames(app: DesktopApp) {
    val long = "חשבון גיבוי עם שם ארוך מאוד מאוד שלא נכנס בשורה אחת Very Long English Account Name"
    val accounts = listOf(
        Account(id = "long1", name = long, apiId = 1, apiHash = "0".repeat(32), phone = "+10000000000"),
        Account(id = "long2", name = "Second account with a really long English name for wrapping", apiId = 1,
            apiHash = "0".repeat(32), phone = "+10000000001"),
    )
    val jobs = listOf(
        Job(id = "longjob1", name = "משימת העתקה עם שם ארוך במיוחד שאמור לשבור שורה Long Job Name",
            source = "https://t.me/c/1000000000/1", target = "https://t.me/c/2000000000/1", accountIds = listOf("long1")),
        Job(id = "longjob2", name = "Copy job with a very long English name that should wrap nicely in the card",
            source = "-1001000000000", target = "-1002000000000", accountIds = listOf("long1", "long2")),
    )
    app.store.updateConfig { it.copy(accounts = accounts, jobs = jobs) }
}

package org.tgbackup.desktop.spike

import io.ktor.http.ContentType
import io.ktor.http.withCharset
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking

/** Serves the spike's Hebrew RTL page on 127.0.0.1 at a free port. */
class LocalPageServer {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** Starts the server and returns the port it is bound to. */
    fun start(): Int {
        check(server == null) { "already started" }
        val created = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/") { call.respondText(PAGE, ContentType.Text.Html.withCharset(Charsets.UTF_8)) }
                get("/health") { call.respondText("ok", ContentType.Text.Plain.withCharset(Charsets.UTF_8)) }
            }
        }
        created.start(wait = false)
        server = created
        return runBlocking { created.engine.resolvedConnectors().first().port }
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        server = null
    }

    companion object {
        val PAGE = """
            <!doctype html>
            <html dir="rtl" lang="he">
            <head><meta charset="utf-8"><title>TG Backup</title>
            <style>
              body { font-family: "Segoe UI", Arial, sans-serif; background: #f3f6fa; color: #1b2733; margin: 0; }
              main { padding: 48px; }
              h1 { font-size: 48px; margin: 0 0 16px; color: #2a7ab0; }
              p { font-size: 22px; }
            </style></head>
            <body><main>
              <h1>שלום! זה TG Backup</h1>
              <p>המסך הזה מגיע משרת מקומי ומוצג בתוך החלון של התוכנה.</p>
              <p>טקסט מימין לשמאל — 123 — English inside.</p>
            </main></body>
            </html>
        """.trimIndent()
    }
}

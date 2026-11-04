package org.tgbackup.desktop.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.tgbackup.desktop.DesktopApp
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** JSON settings shared by every route. */
val TgbJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * The program's internal server: it serves the screens (classpath `web/`) and the API to the program's own
 * WebView2 window, on [host] only. A new [sessionToken] is made on every launch; the window opens [entryUrl].
 */
class ApiServer(
    private val app: DesktopApp,
    private val host: String = "127.0.0.1",
    private val port: Int = 0,
) {
    /** 32 random bytes, hex encoded. */
    val sessionToken: String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var boundPort: Int = -1

    /** Starts the server and returns the bound port. */
    @Synchronized
    fun start(): Int {
        check(server == null) { "already started" }
        // Bind first (port 0 = any free port); the allowed Host values depend on the port we get.
        val hosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val created = embeddedServer(CIO, port = port, host = host) {
            tgbModule(app, sessionToken, hosts)
        }
        created.start(wait = false)
        val actual = runBlocking { created.engine.resolvedConnectors().first().port }
        hosts += setOf("127.0.0.1:$actual", "localhost:$actual")
        server = created
        boundPort = actual
        return actual
    }

    @Synchronized
    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        server = null
    }

    /** The running module's [Events], for publishers outside the routes. */
    fun events(): Events = checkNotNull(server) { "not started" }.application.attributes[EventsKey]

    fun entryUrl(): String {
        check(boundPort > 0) { "not started" }
        return "http://127.0.0.1:$boundPort/?t=$sessionToken"
    }
}

/** Everything the server does, installable on its own with `testApplication`. */
fun Application.tgbModule(app: DesktopApp, token: String, allowedHosts: Set<String>) {
    installSecurity(token, allowedHosts, app.log)
    install(ContentNegotiation) { json(TgbJson) }
    install(SSE)
    val events = Events(app).also { attributes.put(EventsKey, it) }
    val locks = CommandLocks()

    routing {
        // Public: the only route that needs no session cookie (see installSecurity).
        get(HEALTH_PATH) { call.respondText("ok", ContentType.Text.Plain.withCharset(Charsets.UTF_8)) }

        // Authenticated: installSecurity rejects any call without the session cookie before routing,
        // so every route below needs it. Later tasks install their routes here:
        //   fooRoutes(app)   // fun Route.fooRoutes(app: DesktopApp) in its own file
        // Origin is checked only on non-GET requests: never add a GET route that changes state.
        stateRoutes(events)
        jobRoutes(app, locks)
        accountRoutes(app, locks)
        webRoutes()
    }
}

/** The screens, from classpath `web/`. HTML, JS and CSS are never cached, so a new version shows at once. */
private fun Route.webRoutes() {
    staticResources("/", "web", index = "index.html") {
        contentType { url ->
            when (url.path.substringAfterLast('.', "").lowercase()) {
                "html" -> ContentType.Text.Html.withCharset(Charsets.UTF_8)
                "js", "mjs" -> ContentType("text", "javascript").withCharset(Charsets.UTF_8)
                "css" -> ContentType.Text.CSS.withCharset(Charsets.UTF_8)
                "json" -> ContentType.Application.Json.withCharset(Charsets.UTF_8)
                "svg" -> ContentType.Image.SVG
                else -> null
            }
        }
        modify { url, call ->
            if (url.path.substringAfterLast('.', "").lowercase() in setOf("html", "js", "mjs", "css")) {
                call.response.header(HttpHeaders.CacheControl, "no-cache")
            }
        }
    }
}

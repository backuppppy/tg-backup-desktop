package org.tgbackup.desktop.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.tgbackup.desktop.DesktopApp
import java.security.MessageDigest
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
 * [extraRoutes] are installed with the others (behind the same security); the program passes none, only the
 * DevServer passes its test-only routes. [onShow] runs when a second launch asks this one to show its window
 * (`POST /internal/show` with [instanceToken]).
 */
class ApiServer(
    private val app: DesktopApp,
    private val host: String = "127.0.0.1",
    private val port: Int = 0,
    private val extraRoutes: Route.(Events) -> Unit = {},
    private val onShow: () -> Unit = {},
) {
    /** 32 random bytes, hex encoded. */
    val sessionToken: String = randomToken()

    /** For a second launch only (written to `instance.json`); never the session token, which opens the API. */
    val instanceToken: String = randomToken()

    /** The command locks and loaded chats, shared with [RemoteServer]: one program, one set of commands. */
    val locks = CommandLocks()
    val handles = ChatHandles()

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var boundPort: Int = -1

    /** Starts the server and returns the bound port. */
    @Synchronized
    fun start(): Int {
        check(server == null) { "already started" }
        // Bind first (port 0 = any free port); the allowed Host values depend on the port we get.
        val hosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val created = embeddedServer(CIO, port = port, host = host) {
            tgbModule(app, sessionToken, hosts, extraRoutes, handles, locks, instanceToken = instanceToken, onShow = onShow)
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

private fun randomToken(): String =
    ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

/**
 * Everything the server does, installable on its own with `testApplication`. Without an [instanceToken]
 * `POST /internal/show` always answers 403.
 */
fun Application.tgbModule(
    app: DesktopApp,
    token: String,
    allowedHosts: Set<String>,
    extraRoutes: Route.(Events) -> Unit = {},
    handles: ChatHandles = ChatHandles(),
    locks: CommandLocks = CommandLocks(),
    instanceToken: String? = null,
    onShow: () -> Unit = {},
) {
    installSecurity(token, allowedHosts, app.log)
    install(ContentNegotiation) { json(TgbJson) }
    install(SSE)
    val events = Events(app).also { attributes.put(EventsKey, it) }

    routing {
        // Public: the only route that needs no session cookie (see installSecurity).
        get(HEALTH_PATH) { call.respondText("ok", ContentType.Text.Plain.withCharset(Charsets.UTF_8)) }
        // Public too (no cookie), but only for a loopback caller with the instance token.
        internalShowRoute(instanceToken, onShow)

        // Authenticated: installSecurity rejects any call without the session cookie before routing,
        // so every route below needs it.
        appRoutes(app, events, locks, handles, extraRoutes)
    }
}

/**
 * The routes and screens both servers serve (the window's and the phone's). Later tasks install their routes here:
 *   fooRoutes(app)   // fun Route.fooRoutes(app: DesktopApp) in its own file
 * Origin is checked only on non-GET requests: never add a GET route that changes state. A command the phone may
 * not run calls `call.requireLocal()`.
 */
fun Route.appRoutes(
    app: DesktopApp,
    events: Events,
    locks: CommandLocks,
    handles: ChatHandles,
    extraRoutes: Route.(Events) -> Unit,
) {
    stateRoutes(app, events)
    jobRoutes(app, locks)
    accountRoutes(app, locks, handles)
    exportRoutes(app, events, handles)
    remoteRoutes(app)
    extraRoutes(events)
    webRoutes()
}

/** `POST /internal/show`: from loopback with the header [INSTANCE_HEADER] equal to [instanceToken], else 403. */
private fun Route.internalShowRoute(instanceToken: String?, onShow: () -> Unit) {
    val expected = instanceToken?.toByteArray(Charsets.UTF_8)
    post(INTERNAL_SHOW_PATH) {
        val given = call.request.headers[INSTANCE_HEADER]?.toByteArray(Charsets.UTF_8)
        val allowed = expected != null && given != null && MessageDigest.isEqual(given, expected) &&
            isLoopback(call.request.local.remoteAddress)
        if (!allowed) return@post call.respondError(HttpStatusCode.Forbidden, MSG_FORBIDDEN)
        onShow()
        call.respondText("ok", ContentType.Text.Plain.withCharset(Charsets.UTF_8))
    }
}

/**
 * A loopback peer address, as the engine reports it (`127.x.x.x`, `::1`). `localhost` is accepted only for the
 * Ktor test engine, which reports that name; CIO always reports the IP.
 */
internal fun isLoopback(address: String): Boolean {
    val a = address.trim().removePrefix("[").removeSuffix("]").lowercase()
    return a == "localhost" || a == "::1" || a == "0:0:0:0:0:0:0:1" ||
        (a.startsWith("127.") && a.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } })
}

/**
 * The screens, from classpath `web/`. HTML, JS (also the service worker `sw.js`), CSS and the manifest are never
 * cached, so a new version shows at once.
 */
private fun Route.webRoutes() {
    staticResources("/", "web", index = "index.html") {
        contentType { url ->
            when (url.path.substringAfterLast('.', "").lowercase()) {
                "html" -> ContentType.Text.Html.withCharset(Charsets.UTF_8)
                "js", "mjs" -> ContentType("text", "javascript").withCharset(Charsets.UTF_8)
                "css" -> ContentType.Text.CSS.withCharset(Charsets.UTF_8)
                "json" -> ContentType.Application.Json.withCharset(Charsets.UTF_8)
                "webmanifest" -> ContentType("application", "manifest+json").withCharset(Charsets.UTF_8)
                "png" -> ContentType.Image.PNG
                "svg" -> ContentType.Image.SVG
                else -> null
            }
        }
        modify { url, call ->
            if (url.path.substringAfterLast('.', "").lowercase() in setOf("html", "js", "mjs", "css", "webmanifest")) {
                call.response.header(HttpHeaders.CacheControl, "no-cache")
            }
        }
    }
}

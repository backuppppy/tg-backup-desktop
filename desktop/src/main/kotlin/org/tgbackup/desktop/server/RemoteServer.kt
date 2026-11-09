package org.tgbackup.desktop.server

import io.ktor.http.ContentType
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.runBlocking
import org.tgbackup.desktop.DesktopApp
import org.tgbackup.desktop.remote.RemoteAuth
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/** The phone's server port; `tailscale serve` forwards https 443 of the tailnet name here. */
const val REMOTE_PORT = 47800

/** Loopback only: the phone reaches it through `tailscale serve`, never directly. */
private const val HOST = "127.0.0.1"

/**
 * The phone's server: the same routes and screens as the window's [ApiServer], on `127.0.0.1:`[port] only (Tailscale
 * forwards to it), with a password login and a long-lived session cookie ([RemoteAuth]) in place of the entry token.
 * [allowedNames] are the tailnet names the phone may use (read on every request). [secureCookie] is false only for
 * the DevServer's plain-http tests. Pass the window server's [locks] and [handles], so commands from both are one set.
 */
class RemoteServer(
    private val app: DesktopApp,
    private val auth: RemoteAuth,
    private val port: Int = REMOTE_PORT,
    private val allowedNames: () -> Set<String>,
    private val secureCookie: Boolean = true,
    private val handles: ChatHandles = ChatHandles(),
    private val locks: CommandLocks = CommandLocks(),
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** Starts listening; throws [BindException] when the port is taken. */
    @Synchronized
    fun start() {
        check(server == null) { "already started" }
        // A taken port fails here, on this thread: CIO reports it from its accept coroutine, where it would also
        // reach the uncaught-exception handler.
        ServerSocket().use { it.bind(InetSocketAddress(InetAddress.getByName(HOST), port)) }
        val created = embeddedServer(CIO, port = port, host = HOST) {
            remoteModule(app, auth, allowedNames, port, secureCookie, handles, locks)
        }
        try {
            created.start(wait = false)
            runBlocking { created.engine.resolvedConnectors() }
        } catch (e: Throwable) {
            runCatching { created.stop(gracePeriodMillis = 0, timeoutMillis = 1_000) }
            throw bindCause(e) ?: e
        }
        server = created
    }

    @Synchronized
    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        server = null
    }

    private fun bindCause(e: Throwable): BindException? {
        var t: Throwable? = e
        val seen = HashSet<Throwable>()
        while (t != null && seen.add(t)) {
            if (t is BindException) return t
            t = t.cause
        }
        return null
    }
}

/** Everything the phone's server does, installable on its own with `testApplication`. */
fun Application.remoteModule(
    app: DesktopApp,
    auth: RemoteAuth,
    allowedNames: () -> Set<String>,
    port: Int = REMOTE_PORT,
    secureCookie: Boolean = true,
    handles: ChatHandles = ChatHandles(),
    locks: CommandLocks = CommandLocks(),
) {
    installRemoteSecurity(auth, allowedNames, port, app.log)
    install(ContentNegotiation) { json(TgbJson) }
    install(SSE)
    val events = Events(app).also { attributes.put(EventsKey, it) }

    routing {
        // Public (see installRemoteSecurity): the health check and the login.
        get(HEALTH_PATH) { call.respondText("ok", ContentType.Text.Plain.withCharset(Charsets.UTF_8)) }
        remoteLoginRoutes(auth, app.log, secureCookie)

        // Authenticated: the same routes and screens as the window's server.
        appRoutes(app, events, locks, handles) {}
    }
}

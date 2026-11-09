package org.tgbackup.desktop.server

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.renderSetCookieHeader
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tgbackup.app.engine.LogBus
import org.tgbackup.desktop.remote.LoginResult
import org.tgbackup.desktop.remote.RemoteAuth

/** The phone's session cookie (the window's is [SESSION_COOKIE]). */
const val REMOTE_COOKIE = "tgb_remote"

/** Who is asking, per call: [VIEWER_LOCAL] on [ApiServer] (the window), [VIEWER_REMOTE] on [RemoteServer] (the phone). */
val ViewerKey = AttributeKey<String>("tgb.viewer")
const val VIEWER_LOCAL = "local"
const val VIEWER_REMOTE = "remote"

internal const val MSG_PC_ONLY = "זמין רק במחשב"
internal const val MSG_WRONG_PASSWORD = "סיסמה שגויה"
internal const val MSG_UNKNOWN_WHO = "לא ידוע"

/** The login cookie lives 10 years: in practice until the phone logs out. */
private const val REMOTE_COOKIE_MAX_AGE = 10 * 365 * 24 * 60 * 60

/** Paths the phone may load without a session: the login page and what it needs. */
private val PublicGets = setOf("/login.html", "/login.js", "/app.css", "/favicon.png", HEALTH_PATH)
private const val LOGIN_PATH = "/api/login"
private const val LOGOUT_PATH = "/api/logout"

private fun waitText(seconds: Int) = "יותר מדי ניסיונות. אפשר לנסות שוב בעוד $seconds שניות."

/** The login page's request body. */
@Serializable
data class RemoteLoginInput(val password: String = "")

fun ApplicationCall.isRemote(): Boolean = attributes.getOrNull(ViewerKey) == VIEWER_REMOTE

/** [VIEWER_LOCAL] or [VIEWER_REMOTE], for the snapshot. */
internal fun ApplicationCall.viewer(): String = attributes.getOrNull(ViewerKey) ?: VIEWER_LOCAL

/** For window-only commands: a phone gets 403 [MSG_PC_ONLY] (a [Refusal]). */
@Suppress("RedundantSuspendModifier")
suspend fun ApplicationCall.requireLocal() {
    if (isRemote()) throw Refusal(HttpStatusCode.Forbidden, MSG_PC_ONLY)
}

/**
 * The remote request guard, run before routing for every call on [RemoteServer]:
 * 1. `Host`, and `X-Forwarded-Host` when present, must be a name in [allowedNames] (read on every request), with or
 *    without `:443`, or `127.0.0.1:<port>` / `localhost:<port>`; else 403.
 * 2. A non-GET/HEAD request with an `Origin` header must come from `https://<allowed name>` or
 *    `http://127.0.0.1:<port>`; else 403.
 * 3. `/internal/show` is not there (404): the window's calls stay on the window's server.
 * 4. Public without a cookie: the login page, its script, the stylesheet, the icon, `POST /api/login` and
 *    `GET /api/health`. Everything else needs a [REMOTE_COOKIE] that [RemoteAuth.validate]s; else 401
 *    `{"error": ..., "login": true}` (the page goes to `/login.html`), and an HTML page load of `/` is redirected there.
 * Errors and bare statuses are answered as on the window's server.
 */
internal fun Application.installRemoteSecurity(auth: RemoteAuth, allowedNames: () -> Set<String>, port: Int, log: LogBus) {
    intercept(ApplicationCallPipeline.Plugins) {
        call.attributes.put(ViewerKey, VIEWER_REMOTE)
        val method = call.request.httpMethod
        val safe = method == HttpMethod.Get || method == HttpMethod.Head
        val names = allowedNames()

        val forwarded = call.request.headers["X-Forwarded-Host"]
        if (!remoteHostAllowed(call.request.headers[HttpHeaders.Host], names, port) ||
            (forwarded != null && !remoteHostAllowed(forwarded, names, port))
        ) {
            call.respondError(HttpStatusCode.Forbidden, MSG_FORBIDDEN)
            return@intercept finish()
        }
        val origin = call.request.headers[HttpHeaders.Origin]
        if (!safe && origin != null && !remoteOriginAllowed(origin, names, port)) {
            call.respondError(HttpStatusCode.Forbidden, MSG_FORBIDDEN)
            return@intercept finish()
        }

        val path = call.request.path().ifEmpty { "/" }
        if (path == INTERNAL_SHOW_PATH) {
            call.respondError(HttpStatusCode.NotFound, MSG_NOT_FOUND)
            return@intercept finish()
        }
        val public = (method == HttpMethod.Get && path in PublicGets) || (method == HttpMethod.Post && path == LOGIN_PATH)
        if (!public && !auth.validate(call.request.cookies.rawCookies[REMOTE_COOKIE])) {
            if (method == HttpMethod.Get && (path == "/" || path == "/index.html")) {
                call.respondRedirect("/login.html")
            } else {
                call.respondText(
                    buildJsonObject {
                        put("error", MSG_LOGIN_REQUIRED)
                        put("login", true)
                    }.toString(),
                    ContentType.Application.Json.withCharset(Charsets.UTF_8),
                    HttpStatusCode.Unauthorized,
                )
            }
            return@intercept finish()
        }

        proceedReportingErrors(call, path, log) { proceed() }
    }
    installJsonErrorStatuses()
}

/**
 * `POST /api/login {password}`: 200 with the [REMOTE_COOKIE], 401 [MSG_WRONG_PASSWORD], or 429 `{"error", "seconds"}`
 * while the caller (or everyone) must wait. `POST /api/logout` ends this session only and clears the cookie.
 * Neither the password nor the session value is ever logged.
 */
internal fun Route.remoteLoginRoutes(auth: RemoteAuth, log: LogBus, secureCookie: Boolean) {
    post(LOGIN_PATH) {
        val input = call.receive<RemoteLoginInput>()
        val who = whoOf(call)
        // PBKDF2 takes a while: off the server's threads.
        when (val result = withContext(Dispatchers.IO) { auth.login(input.password, who) }) {
            is LoginResult.Ok -> {
                call.response.headers.append(HttpHeaders.SetCookie, remoteCookie(result.session, REMOTE_COOKIE_MAX_AGE, secureCookie))
                log.info("מכשיר התחבר מהטלפון ($who)")
                call.respondOk()
            }
            LoginResult.Wrong -> {
                log.warn("ניסיון כניסה כושל ($who)")
                call.respondError(HttpStatusCode.Unauthorized, MSG_WRONG_PASSWORD)
            }
            is LoginResult.Wait -> call.respondText(
                buildJsonObject {
                    put("error", waitText(result.seconds))
                    put("seconds", result.seconds)
                }.toString(),
                ContentType.Application.Json.withCharset(Charsets.UTF_8),
                HttpStatusCode.TooManyRequests,
            )
        }
    }

    post(LOGOUT_PATH) {
        call.request.cookies.rawCookies[REMOTE_COOKIE]?.let { auth.logout(it) }
        call.response.headers.append(HttpHeaders.SetCookie, remoteCookie("", 0, secureCookie))
        call.respondOk()
    }
}

/**
 * The Set-Cookie value, rendered by hand: Ktor refuses a `Secure` cookie over plain http, and the phone's requests
 * arrive over http from `tailscale serve` (the phone itself is on https).
 */
private fun remoteCookie(value: String, maxAge: Int, secure: Boolean): String = renderSetCookieHeader(
    Cookie(
        name = REMOTE_COOKIE,
        value = value,
        encoding = CookieEncoding.RAW,
        maxAge = maxAge,
        path = "/",
        secure = secure,
        httpOnly = true,
        extensions = mapOf("SameSite" to "Strict"),
    ),
)

/** The log's and the wait's name for the caller: Tailscale's user login, else the first forwarded address. */
internal fun whoOf(call: ApplicationCall): String {
    call.request.headers["Tailscale-User-Login"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return MSG_UNKNOWN_WHO
}

private fun cleanName(name: String): String = name.trim().lowercase().removeSuffix(".")

/** [host] is an allowed name (with or without `:443`), or loopback on [port]. */
internal fun remoteHostAllowed(host: String?, names: Set<String>, port: Int): Boolean {
    val h = host?.trim()?.lowercase() ?: return false
    if (h == "127.0.0.1:$port" || h == "localhost:$port") return true
    return names.map(::cleanName).any { it.isNotEmpty() && (h == it || h == "$it:443") }
}

/** [origin] is `https://<allowed name>` (or with `:443`), or `http://127.0.0.1:<port>`. */
internal fun remoteOriginAllowed(origin: String, names: Set<String>, port: Int): Boolean {
    val o = origin.trim().lowercase()
    if (o == "http://127.0.0.1:$port") return true
    if (!o.startsWith("https://")) return false
    val h = o.removePrefix("https://")
    return names.map(::cleanName).any { it.isNotEmpty() && (h == it || h == "$it:443") }
}

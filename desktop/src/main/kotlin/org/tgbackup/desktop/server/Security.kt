package org.tgbackup.desktop.server

import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.http.content.HttpStatusCodeContent
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.request.ContentTransformationException
import io.ktor.server.request.httpMethod
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.request.path
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tgbackup.app.engine.LogBus
import java.security.MessageDigest

const val SESSION_COOKIE = "tgb_session"
const val HEALTH_PATH = "/api/health"

internal const val MSG_LOGIN_REQUIRED = "נדרשת התחברות"
internal const val MSG_FORBIDDEN = "הבקשה נחסמה"
internal const val MSG_NOT_FOUND = "לא נמצא"
internal const val MSG_INTERNAL = "שגיאה פנימית"
internal const val MSG_BAD_REQUEST = "בקשה שגויה"
internal const val MSG_UNSUPPORTED_TYPE = "סוג תוכן לא נתמך"
internal const val MSG_TOO_LARGE = "הבקשה גדולה מדי"

/** Every error the server sends: `{"error": "<Hebrew text>"}`. */
suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) {
    respondText(
        buildJsonObject { put("error", message) }.toString(),
        ContentType.Application.Json.withCharset(Charsets.UTF_8),
        status,
    )
}

/**
 * The request guard, run before routing for every call:
 * 1. `Host` must be one of [allowedHosts] (DNS-rebinding guard), else 403.
 * 2. A non-GET/HEAD request with an `Origin` header must come from `http://<allowed host>`, else 403.
 *    [allowedHosts] is read on every request: ApiServer fills it only after the port is bound.
 * 3. `GET /?t=<token>` with the right token sets the session cookie and redirects to `/`; a wrong token is 401.
 * 4. Everything except `GET /api/health` needs the session cookie, else 401.
 * Bare error statuses (such as routing's 404) are sent as JSON, and exceptions as a JSON 500.
 */
internal fun Application.installSecurity(token: String, allowedHosts: Set<String>, log: LogBus) {
    val tokenBytes = token.toByteArray(Charsets.UTF_8)

    fun matches(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), tokenBytes)

    intercept(ApplicationCallPipeline.Plugins) {
        val method = call.request.httpMethod
        val safe = method == HttpMethod.Get || method == HttpMethod.Head

        if (normalizeHost(call.request.headers[HttpHeaders.Host]) !in allowedHosts) {
            call.respondError(HttpStatusCode.Forbidden, MSG_FORBIDDEN)
            return@intercept finish()
        }
        val origin = call.request.headers[HttpHeaders.Origin]
        if (!safe && origin != null && !originAllowed(origin, allowedHosts)) {
            call.respondError(HttpStatusCode.Forbidden, MSG_FORBIDDEN)
            return@intercept finish()
        }

        val path = call.request.path().ifEmpty { "/" }
        val public = method == HttpMethod.Get && path == HEALTH_PATH
        val entryToken = call.request.queryParameters["t"]
        if (!public && method == HttpMethod.Get && path == "/" && entryToken != null) {
            if (matches(entryToken)) {
                call.response.cookies.append(
                    Cookie(
                        name = SESSION_COOKIE,
                        value = token,
                        encoding = CookieEncoding.RAW,
                        path = "/",
                        httpOnly = true,
                        extensions = mapOf("SameSite" to "Strict"),
                    ),
                )
                call.respondRedirect("/")
            } else {
                call.respondError(HttpStatusCode.Unauthorized, MSG_LOGIN_REQUIRED)
            }
            return@intercept finish()
        }
        if (!public && !matches(call.request.cookies.rawCookies[SESSION_COOKIE])) {
            call.respondError(HttpStatusCode.Unauthorized, MSG_LOGIN_REQUIRED)
            return@intercept finish()
        }

        try {
            proceed()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val (status, message) = when (e) {
                is BadRequestException, is ContentTransformationException ->
                    HttpStatusCode.BadRequest to MSG_BAD_REQUEST
                is NotFoundException -> HttpStatusCode.NotFound to MSG_NOT_FOUND
                is UnsupportedMediaTypeException -> HttpStatusCode.UnsupportedMediaType to MSG_UNSUPPORTED_TYPE
                is PayloadTooLargeException -> HttpStatusCode.PayloadTooLarge to MSG_TOO_LARGE
                else -> {
                    log.error("$MSG_INTERNAL (${call.request.httpMethod.value} $path): ${describeForLog(e)}")
                    HttpStatusCode.InternalServerError to MSG_INTERNAL
                }
            }
            if (!call.response.isCommitted) call.respondError(status, message)
        }
    }

    // Bare status replies (routing's own 404/405, `call.respond(HttpStatusCode.X)`) become JSON errors too.
    sendPipeline.intercept(ApplicationSendPipeline.Before) { subject ->
        val status = when (subject) {
            is HttpStatusCode -> subject
            is HttpStatusCodeContent -> subject.status
            else -> null
        }
        if (status != null && status.value >= 400) {
            proceedWith(
                TextContent(
                    buildJsonObject { put("error", messageFor(status)) }.toString(),
                    ContentType.Application.Json.withCharset(Charsets.UTF_8),
                    status,
                ),
            )
        }
    }
}

private fun messageFor(status: HttpStatusCode): String = when (status) {
    HttpStatusCode.Unauthorized -> MSG_LOGIN_REQUIRED
    HttpStatusCode.Forbidden -> MSG_FORBIDDEN
    HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed -> MSG_NOT_FOUND
    HttpStatusCode.BadRequest -> MSG_BAD_REQUEST
    HttpStatusCode.UnsupportedMediaType -> MSG_UNSUPPORTED_TYPE
    HttpStatusCode.PayloadTooLarge -> MSG_TOO_LARGE
    else -> MSG_INTERNAL
}

/** [origin] is `http://<host>` for one of [allowedHosts] (a missing port means 80). */
internal fun originAllowed(origin: String, allowedHosts: Set<String>): Boolean =
    origin.startsWith("http://") && normalizeHost(origin.removePrefix("http://")) in allowedHosts

/**
 * Exception class names (with causes) and stack frames, for the log. Messages are left out on purpose:
 * parse errors quote the request input, which can hold a login code or a 2FA password.
 */
internal fun describeForLog(e: Throwable, maxFrames: Int = 20): String = buildString {
    var t: Throwable? = e
    var first = true
    val seen = HashSet<Throwable>()
    while (t != null && seen.add(t)) {
        append(if (first) t.javaClass.name else "\nCaused by: ${t.javaClass.name}")
        t.stackTrace.take(maxFrames).forEach { append("\n    at ").append(it) }
        first = false
        t = t.cause
    }
}

/** `Host` as `name:port`; a header without a port means the HTTP default 80. */
internal fun normalizeHost(host: String?): String? {
    if (host.isNullOrBlank()) return null
    val h = host.trim().lowercase()
    val hasPort = if (h.startsWith("[")) h.contains("]:") else h.contains(':')
    return if (hasPort) h else "$h:80"
}

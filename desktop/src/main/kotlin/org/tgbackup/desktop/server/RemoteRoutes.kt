package org.tgbackup.desktop.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.nayuki.qrcodegen.QrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.tgbackup.desktop.DesktopApp

private const val MIN_PASSWORD = 6
private const val MSG_SHORT_PASSWORD = "לפחות 6 תווים"
private const val MSG_PASSWORD_FIRST = "קודם קובעים סיסמה"

/** Modules of white around the code, as the QR standard asks. */
private const val QR_BORDER = 4

@Serializable
data class RemotePasswordInput(val password: String = "")

@Serializable
data class RemoteEnabledInput(val enabled: Boolean = false)

/**
 * The phone access in Settings: `GET /api/remote` (the status), `PUT /api/remote/password {password}`,
 * `PUT /api/remote/enabled {enabled}` (409 without a password), `POST /api/remote/disconnect-all`, and
 * `GET /api/remote/qr.svg` (the QR of the url, 404 unless running). The writes are for the PC only.
 */
fun Route.remoteRoutes(app: DesktopApp) {
    val remote = app.remote

    get("/api/remote") { call.respond(remote.status.value) }

    put("/api/remote/password") {
        command {
            call.requireLocal()
            val input = call.receive<RemotePasswordInput>()
            if (input.password.length < MIN_PASSWORD) throw Refusal(HttpStatusCode.BadRequest, MSG_SHORT_PASSWORD)
            // PBKDF2 takes a while: off the server's threads.
            withContext(Dispatchers.IO) { remote.setPassword(input.password) }
        }
    }

    put("/api/remote/enabled") {
        command {
            call.requireLocal()
            val input = call.receive<RemoteEnabledInput>()
            if (!remote.setEnabled(input.enabled)) throw Refusal(HttpStatusCode.Conflict, MSG_PASSWORD_FIRST)
        }
    }

    post("/api/remote/disconnect-all") {
        command {
            call.requireLocal()
            remote.disconnectAll()
        }
    }

    get("/api/remote/qr.svg") {
        val status = remote.status.value
        val url = status.url
        if (status.state != "Running" || url == null) return@get call.respondError(HttpStatusCode.NotFound, MSG_NOT_FOUND)
        // Never from the cache: a new address must show its own code.
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(qrSvg(url), ContentType.Image.SVG)
    }
}

/** [text] as a QR code: black squares on white, one SVG unit per module. */
internal fun qrSvg(text: String): String {
    val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
    val size = qr.size + QR_BORDER * 2
    val path = StringBuilder()
    for (y in 0 until qr.size) {
        for (x in 0 until qr.size) {
            if (qr.getModule(x, y)) path.append("M${x + QR_BORDER},${y + QR_BORDER}h1v1h-1z")
        }
    }
    return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $size $size" shape-rendering="crispEdges">""" +
        """<rect width="100%" height="100%" fill="#ffffff"/><path d="$path" fill="#000000"/></svg>"""
}

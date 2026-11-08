package org.tgbackup.desktop.server

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import kotlinx.serialization.Serializable
import org.tgbackup.desktop.DesktopApp
import kotlin.time.Duration.Companion.seconds

/** How often an idle event stream sends a comment, so a dead connection is noticed. */
internal val SSE_PING_PERIOD = 15.seconds

/** The Settings switches; a field left out keeps its value. */
@Serializable
data class DesktopPrefsInput(val autostart: Boolean? = null, val keepAwake: Boolean? = null)

/**
 * `GET /api/state` (the snapshot), `GET /api/events` (the live stream), `DELETE /api/log`, which empties the
 * log screen's buffer (LogBus.clear: the log files are untouched), and `GET`/`PUT /api/desktop-prefs` (Settings).
 */
fun Route.stateRoutes(app: DesktopApp, events: Events) {
    get("/api/state") { call.respond(events.snapshot()) }

    get("/api/desktop-prefs") { call.respond(app.prefs.value.value) }

    put("/api/desktop-prefs") {
        command {
            val input = call.receive<DesktopPrefsInput>()
            app.prefs.update { p ->
                p.copy(autostart = input.autostart ?: p.autostart, keepAwake = input.keepAwake ?: p.keepAwake)
            }
        }
    }

    delete("/api/log") {
        app.log.clear()
        call.respondOk()
    }

    sse("/api/events") {
        heartbeat {
            period = SSE_PING_PERIOD
            event = io.ktor.sse.ServerSentEvent(comments = "ping")
        }
        // A failed write (client gone) or the call's cancellation ends the collect and every flow under it.
        events.stream().collect { send(it) }
    }
}

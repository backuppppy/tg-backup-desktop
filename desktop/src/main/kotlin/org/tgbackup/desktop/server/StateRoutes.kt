package org.tgbackup.desktop.server

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import org.tgbackup.desktop.DesktopApp
import kotlin.time.Duration.Companion.seconds

/** How often an idle event stream sends a comment, so a dead connection is noticed. */
internal val SSE_PING_PERIOD = 15.seconds

/**
 * `GET /api/state` (the snapshot), `GET /api/events` (the live stream) and `DELETE /api/log`, which empties the
 * log screen's buffer (LogBus.clear: the log files are untouched).
 */
fun Route.stateRoutes(app: DesktopApp, events: Events) {
    get("/api/state") { call.respond(events.snapshot()) }

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

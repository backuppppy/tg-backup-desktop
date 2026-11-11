package org.tgbackup.desktop

import io.sentry.Sentry
import io.sentry.SentryEvent
import org.tgbackup.app.engine.SentryOnce
import java.nio.file.Path

/** Sentry for the desktop program: no personal data, never under `--smoke`. */
object Telemetry {
    private val PHONE = Regex("\\+?\\d{9,15}")

    /** Returns true when Sentry was started; false (nothing started) under `--smoke` or with a blank [dsn]. */
    fun init(dataDir: Path, dsn: String, smoke: Boolean): Boolean {
        if (smoke || dsn.isBlank()) return false
        Sentry.init { o ->
            o.dsn = dsn
            o.release = "tgbackup-desktop@$VERSION"
            o.environment = "production"
            o.isSendDefaultPii = false
            o.beforeSend = io.sentry.SentryOptions.BeforeSendCallback { event, _ -> scrub(event) }
        }
        SentryOnce.init(dataDir.toFile())
        return true
    }

    internal fun scrub(event: SentryEvent): SentryEvent {
        event.user = null
        event.serverName = null
        event.request = null
        event.message?.let { m ->
            m.message = m.message?.let(::mask)
            m.formatted = m.formatted?.let(::mask)
        }
        event.exceptions?.forEach { it.value = it.value?.let(::mask) }
        return event
    }

    private fun mask(s: String): String = PHONE.replace(s, "<num>")
}

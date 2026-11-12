package org.tgbackup.desktop

import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import org.tgbackup.app.engine.SentryOnce
import java.nio.file.Path

/** Sentry for the desktop program: no personal data, never under `--smoke`. */
object Telemetry {
    /** A run of digits with separators (`+972 50-123-4567`, `(050) 123.4567`). */
    private val NUMBER = Regex("\\+?\\d[\\d\\s\\-().]{7,}\\d")

    /** Separators allowed between the digits of a configured phone number. */
    private const val SEPARATORS = "[\\s\\-().]*"

    /**
     * The configured accounts' phone numbers, masked as `<phone>` in any format (compared on their digits).
     * Set by Main once the app exists; until then none.
     */
    @Volatile
    var phones: () -> List<String> = { emptyList() }

    /** Returns true when Sentry was started; false (nothing started) under `--smoke` or with a blank [dsn]. */
    fun init(dataDir: Path, dsn: String, smoke: Boolean): Boolean {
        if (smoke || dsn.isBlank()) return false
        Sentry.init { o ->
            o.dsn = dsn
            o.release = "tgbackup-desktop@$VERSION"
            o.environment = "production"
            o.isSendDefaultPii = false
            o.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> scrub(event) }
            // Every log line becomes a breadcrumb (LogBus), and an account's lines carry its name or phone.
            o.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { crumb, _ -> scrubBreadcrumb(crumb) }
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
        event.breadcrumbs?.forEach { scrubBreadcrumb(it) }
        event.extras?.entries?.toList()?.forEach { (k, v) -> if (v is String) event.setExtra(k, mask(v)) }
        event.tags?.entries?.toList()?.forEach { (k, v) -> v?.let { event.setTag(k, mask(it)) } }
        return event
    }

    internal fun scrubBreadcrumb(crumb: Breadcrumb): Breadcrumb {
        crumb.message = crumb.message?.let(::mask)
        crumb.data.entries.toList().forEach { (k, v) -> if (v is String) crumb.setData(k, mask(v)) }
        return crumb
    }

    private fun mask(s: String): String {
        var out = s
        runCatching { phones() }.getOrDefault(emptyList()).forEach { phone ->
            val digits = phone.filter(Char::isDigit)
            // A blank or very short entry would match any number (or everything).
            if (digits.length >= 5) {
                out = Regex("\\+?\\(?" + digits.toCharArray().joinToString(SEPARATORS)).replace(out, "<phone>")
            }
        }
        val home = System.getProperty("user.home").orEmpty().trimEnd('\\', '/')
        if (home.length > 1) out = out.replace(home, "<home>", ignoreCase = true)
        return NUMBER.replace(out, "<num>")
    }
}

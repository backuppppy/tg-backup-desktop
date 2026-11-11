package org.tgbackup.desktop

import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TelemetryTest {
    private val dsn = "https://k@o1.ingest.de.sentry.io/1"

    @Test
    fun smokeDoesNotInit() {
        val dir = Files.createTempDirectory("tel")
        assertFalse(Telemetry.init(dir, dsn, smoke = true))
        assertFalse(Sentry.isEnabled())
    }

    @Test
    fun blankDsnDoesNotInit() {
        val dir = Files.createTempDirectory("tel")
        assertFalse(Telemetry.init(dir, "  ", smoke = false))
        assertFalse(Sentry.isEnabled())
    }

    @Test
    fun scrubDropsUserAndServer() {
        val e = SentryEvent().apply {
            user = User().apply { id = "1"; username = "x" }
            serverName = "host"
            request = Request().apply { url = "http://a"; data = "secret" }
        }
        val out = Telemetry.scrub(e)
        assertNull(out.user)
        assertNull(out.serverName)
        assertNull(out.request)
    }

    @Test
    fun scrubMasksPhoneNumbers() {
        val e = SentryEvent().apply {
            message = Message().apply { message = "login +972501234567 failed"; formatted = "login +972501234567 failed" }
            setExceptions(listOf(SentryException().apply { value = "bad 972501234567" }))
        }
        val out = Telemetry.scrub(e)
        assertEquals("login <num> failed", out.message!!.message)
        assertEquals("login <num> failed", out.message!!.formatted)
        assertEquals("bad <num>", out.exceptions!!.first().value)
    }
}

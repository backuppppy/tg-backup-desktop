package org.tgbackup.desktop

import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @AfterTest
    fun noPhones() {
        Telemetry.phones = { emptyList() }
    }

    private fun noPhoneDigits(s: String?) = assertTrue(s != null && !Regex("\\d{3}").containsMatchIn(s), "digits left in: $s")

    @Test
    fun scrubMasksBreadcrumbs() {
        val e = SentryEvent().apply {
            breadcrumbs = mutableListOf(Breadcrumb("[+972 50-123-4567] סשן הייצוא מחובר").apply { setData("line", "+972 (50) 123.4567") })
        }
        val crumb = Telemetry.scrub(e).breadcrumbs!!.single()
        noPhoneDigits(crumb.message)
        assertTrue(crumb.message!!.endsWith("סשן הייצוא מחובר"))
        noPhoneDigits(crumb.getData("line") as String)
    }

    @Test
    fun beforeBreadcrumbMasksMessageAndData() {
        val b = Telemetry.scrubBreadcrumb(Breadcrumb("[+972 50-123-4567] x").apply { setData("k", "050-123-4567") })
        noPhoneDigits(b.message)
        noPhoneDigits(b.getData("k") as String)
    }

    @Test
    fun scrubMasksExtrasAndTags() {
        val e = SentryEvent().apply {
            setExtra("line", "login +972 50-123-4567")
            setExtra("count", 3)
            setTag("account", "+972501234567")
        }
        val out = Telemetry.scrub(e)
        assertEquals("login <num>", out.getExtra("line"))
        assertEquals(3, out.getExtra("count"))
        assertEquals("<num>", out.getTag("account"))
    }

    @Test
    fun aConfiguredPhoneIsMaskedInAnyFormat() {
        Telemetry.phones = { listOf("+972501234567", "") }
        val e = SentryEvent().apply {
            message = Message().apply { message = "a 972-50-123-4567 b +972501234567 c (972) 50 1234567" }
        }
        assertEquals("a <phone> b <phone> c <phone>", Telemetry.scrub(e).message!!.message)
    }

    @Test
    fun theHomeDirIsMasked() {
        val home = System.getProperty("user.home")
        val e = SentryEvent().apply {
            setExceptions(listOf(SentryException().apply { value = "cannot open $home/x.txt" }))
        }
        assertEquals("cannot open <home>/x.txt", Telemetry.scrub(e).exceptions!!.first().value)
    }
}

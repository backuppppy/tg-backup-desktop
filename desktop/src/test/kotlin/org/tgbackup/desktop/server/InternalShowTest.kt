package org.tgbackup.desktop.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.tgbackup.desktop.HandOff
import org.tgbackup.desktop.SingleInstance
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InternalShowTest : RouteTestBase() {
    private val shows = AtomicInteger()

    @AfterTest
    fun tearDown() = closeApp()

    /** No session cookie: a second launch has only the instance token. */
    private fun withShowServer(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            tgbModule(
                app = app,
                token = "t1",
                allowedHosts = setOf("localhost:80"),
                instanceToken = "i1",
                onShow = { shows.incrementAndGet() },
            )
        }
        block(createClient { })
    }

    @Test
    fun withoutTheTokenIs403() = withShowServer { client ->
        val r = client.post(INTERNAL_SHOW_PATH) { header(HttpHeaders.Host, "localhost:80") }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals(0, shows.get())
    }

    @Test
    fun aWrongTokenIs403() = withShowServer { client ->
        val r = client.post(INTERNAL_SHOW_PATH) {
            header(HttpHeaders.Host, "localhost:80")
            header(INSTANCE_HEADER, "i2")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals(0, shows.get())
    }

    @Test
    fun theSessionTokenIsNotTheInstanceToken() = withShowServer { client ->
        val r = client.post(INTERNAL_SHOW_PATH) {
            header(HttpHeaders.Host, "localhost:80")
            header(INSTANCE_HEADER, "t1")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun theRightTokenWithoutACookieShowsTheWindow() = withShowServer { client ->
        val r = client.post(INTERNAL_SHOW_PATH) {
            header(HttpHeaders.Host, "localhost:80")
            header(INSTANCE_HEADER, "i1")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(1, shows.get())
    }

    @Test
    fun theHostCheckStillApplies() = withShowServer { client ->
        val r = client.post(INTERNAL_SHOW_PATH) {
            header(HttpHeaders.Host, "evil.example")
            header(INSTANCE_HEADER, "i1")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals(0, shows.get())
    }

    @Test
    fun aForeignOriginIs403() = withShowServer { client ->
        val r = client.post(INTERNAL_SHOW_PATH) {
            header(HttpHeaders.Host, "localhost:80")
            header(HttpHeaders.Origin, "http://evil.example")
            header(INSTANCE_HEADER, "i1")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals(0, shows.get())
    }

    @Test
    fun aGetStillNeedsTheCookie() = withShowServer { client ->
        val r = client.get(INTERNAL_SHOW_PATH) {
            header(HttpHeaders.Host, "localhost:80")
            header(INSTANCE_HEADER, "i1")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals(0, shows.get())
    }

    @Test
    fun withoutAnInstanceTokenTheRouteIsAlways403() = testApplication {
        application { tgbModule(app = app, token = "t1", allowedHosts = setOf("localhost:80")) }
        val r = createClient { }.post(INTERNAL_SHOW_PATH) {
            header(HttpHeaders.Host, "localhost:80")
            header(INSTANCE_HEADER, "")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun onlyLoopbackAddressesCount() {
        assertTrue(isLoopback("127.0.0.1"))
        assertTrue(isLoopback("127.5.6.7"))
        assertTrue(isLoopback("::1"))
        assertTrue(isLoopback("0:0:0:0:0:0:0:1"))
        assertTrue(isLoopback("localhost"))
        assertFalse(isLoopback("192.168.1.5"))
        assertFalse(isLoopback("10.0.0.1"))
        assertFalse(isLoopback(""))
        assertFalse(isLoopback("evil.example"))
    }

    @Test
    fun aSecondLaunchHandsOffToTheRealServer() {
        val server = ApiServer(app, onShow = { shows.incrementAndGet() })
        val port = server.start()
        val dir = Files.createTempDirectory("handoff")
        val owner = SingleInstance(dir)
        try {
            assertTrue(owner.acquire())
            owner.publish(port, server.instanceToken)
            assertEquals(HandOff.Shown, SingleInstance(dir).handOff())
            assertEquals(1, shows.get())
            assertTrue(server.instanceToken != server.sessionToken)
        } finally {
            owner.release()
            server.stop()
        }
    }
}

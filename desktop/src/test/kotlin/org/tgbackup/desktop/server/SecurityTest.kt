package org.tgbackup.desktop.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.client.request.setBody
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.Serializable
import org.tgbackup.app.engine.Level
import io.ktor.server.testing.testApplication
import org.tgbackup.desktop.DesktopApp
import org.tgbackup.desktop.PowerRequests
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Adds `Host: localhost:80` unless the request sets its own. */
private val DefaultHost = createClientPlugin("DefaultHost") {
    onRequest { request, _ ->
        if (!request.headers.contains(HttpHeaders.Host)) request.headers.append(HttpHeaders.Host, "localhost:80")
    }
}

@Serializable
private data class Probe(val name: String)

/** Routes that exist only in tests, installed after tgbModule (the security guard covers them too). */
private fun Route.testOnlyRoutes() {
    post("/test/echo") { call.respondText(call.receive<Probe>().name) }
    get("/test/boom") { throw IllegalStateException("boom secret-2fa-123") }
}

class SecurityTest {
    /** Data dir with a Hebrew name and a space; no-op power requests. */
    private val app = DesktopApp(
        Files.createTempDirectory("security-test").resolve("תיקייה עם רווח"),
        PowerRequests { },
    )

    @AfterTest
    fun closeApp() = app.close()

    private fun withServer(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application {
            tgbModule(app = app, token = "t1", allowedHosts = setOf("localhost:80"))
            routing { testOnlyRoutes() }
        }
        block(
            createClient {
                followRedirects = false
                // The test engine sends no Host header by default; a browser always does.
                install(DefaultHost)
            },
        )
    }

    @Test
    fun healthNeedsNoCookie() = withServer { client ->
        val r = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("ok", r.bodyAsText())
    }

    @Test
    fun noCookieIs401() = withServer { client ->
        val r = client.get("/")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertTrue(r.bodyAsText().contains("נדרשת התחברות"), r.bodyAsText())
        assertEquals(ContentType.Application.Json, r.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it).withoutParameters() })
    }

    @Test
    fun tokenSetsCookieAndRedirects() = withServer { client ->
        val r = client.get("/?t=t1")
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/", r.headers[HttpHeaders.Location])
        val cookie = assertNotNull(r.headers[HttpHeaders.SetCookie])
        assertTrue(cookie.contains("tgb_session=t1"), cookie)
        assertTrue(cookie.contains("HttpOnly"), cookie)
        assertTrue(cookie.contains("SameSite=Strict"), cookie)
        assertTrue(cookie.contains("Path=/"), cookie)
    }

    @Test
    fun wrongTokenIs401() = withServer { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/?t=bad").status)
    }

    @Test
    fun wrongHostIs403() = withServer { client ->
        val r = client.get("/") {
            header(HttpHeaders.Cookie, "tgb_session=t1")
            header(HttpHeaders.Host, "evil.example")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("\"error\""), r.bodyAsText())
    }

    @Test
    fun localhostWithoutPortMeansPort80() = withServer { client ->
        val r = client.get("/api/health") { header(HttpHeaders.Host, "localhost") }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun foreignOriginPostIs403() = withServer { client ->
        val r = client.post("/api/anything") {
            header(HttpHeaders.Cookie, "tgb_session=t1")
            header(HttpHeaders.Origin, "http://evil.example")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun sameOriginPostPassesTheChecks() = withServer { client ->
        val r = client.post("/api/anything") {
            header(HttpHeaders.Cookie, "tgb_session=t1")
            header(HttpHeaders.Origin, "http://localhost:80")
        }
        // No such route yet: past the security checks, so a JSON 404 rather than 401/403.
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("\"error\""), r.bodyAsText())
    }

    @Test
    fun wrongCookieIs401() = withServer { client ->
        val r = client.get("/") { header(HttpHeaders.Cookie, "tgb_session=t2") }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun indexServedWithCookie() = withServer { client ->
        val r = client.get("/") { header(HttpHeaders.Cookie, "tgb_session=t1") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("dir=\"rtl\""), r.bodyAsText())
        assertEquals("no-cache", r.headers[HttpHeaders.CacheControl])
        assertTrue(r.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/html"))
    }

    @Test
    fun sessionTokenIs32RandomBytesInHex() {
        val a = ApiServer(app)
        val b = ApiServer(app)
        assertTrue(Regex("[0-9a-f]{64}").matches(a.sessionToken), a.sessionToken)
        assertTrue(a.sessionToken != b.sessionToken)
    }

    @Test
    fun jsServedAsModuleWithNoCache() = withServer { client ->
        val r = client.get("/probe.js") { header(HttpHeaders.Cookie, "tgb_session=t1") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("text/javascript; charset=UTF-8", r.headers[HttpHeaders.ContentType])
        assertEquals("no-cache", r.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun theManifestIsManifestJsonAndTheWorkerIsNoCache() = withServer { client ->
        val m = client.get("/manifest.webmanifest") { header(HttpHeaders.Cookie, "tgb_session=t1") }
        assertEquals(HttpStatusCode.OK, m.status)
        assertEquals("application/manifest+json", m.headers[HttpHeaders.ContentType]?.substringBefore(';'))
        assertTrue(m.bodyAsText().contains("\"start_url\""), m.bodyAsText())
        val sw = client.get("/sw.js") { header(HttpHeaders.Cookie, "tgb_session=t1") }
        assertEquals(HttpStatusCode.OK, sw.status)
        assertEquals("text/javascript; charset=UTF-8", sw.headers[HttpHeaders.ContentType])
        assertEquals("no-cache", sw.headers[HttpHeaders.CacheControl])
        val icon = client.get("/icon-192.png") { header(HttpHeaders.Cookie, "tgb_session=t1") }
        assertEquals("image/png", icon.headers[HttpHeaders.ContentType])
    }

    @Test
    fun staticFileNeedsTheCookie() = withServer { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/probe.js").status)
    }

    @Test
    fun realServerOnLoopbackWithEntryUrl() {
        val server = ApiServer(app)
        val port = server.start()
        try {
            assertTrue(port > 0)
            assertEquals("http://127.0.0.1:$port/?t=${server.sessionToken}", server.entryUrl())
            val http = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()
            fun get(url: String, cookie: String? = null) = http.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI(url))
                    .apply { if (cookie != null) header("Cookie", cookie) }.build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, get("http://127.0.0.1:$port/api/health").statusCode())
            assertEquals(200, get("http://localhost:$port/api/health").statusCode())
            val entry = get(server.entryUrl())
            assertEquals(302, entry.statusCode())
            val cookie = entry.headers().firstValue("Set-Cookie").get().substringBefore(';')
            assertEquals("tgb_session=${server.sessionToken}", cookie)
            val index = get("http://127.0.0.1:$port/", cookie)
            assertEquals(200, index.statusCode())
            assertTrue(index.body().contains("<title>TG Backup</title>"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun malformedJsonIs400() = withServer { client ->
        val r = client.post("/test/echo") {
            header(HttpHeaders.Cookie, "tgb_session=t1")
            contentType(ContentType.Application.Json)
            setBody("{\"name\": \"secret-code-5555\"")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("בקשה שגויה"), r.bodyAsText())
        assertTrue(app.log.entries.value.none { it.msg.contains("secret-code-5555") })
    }

    @Test
    fun wellFormedJsonReachesTheRoute() = withServer { client ->
        val r = client.post("/test/echo") {
            header(HttpHeaders.Cookie, "tgb_session=t1")
            contentType(ContentType.Application.Json)
            setBody("{\"name\": \"x\"}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("x", r.bodyAsText())
    }

    @Test
    fun unexpectedExceptionIs500AndLogged() = withServer { client ->
        val r = client.get("/test/boom") { header(HttpHeaders.Cookie, "tgb_session=t1") }
        assertEquals(HttpStatusCode.InternalServerError, r.status)
        assertTrue(r.bodyAsText().contains("שגיאה פנימית"), r.bodyAsText())
        val errors = app.log.entries.value.filter { it.level == Level.ERROR }
        assertTrue(errors.any { it.msg.contains("IllegalStateException") && it.msg.contains("/test/boom") }, errors.toString())
        assertTrue(errors.none { it.msg.contains("secret-2fa-123") }, "exception messages must not reach the log")
    }

    @Test
    fun realServerAcceptsSameOriginPost() {
        val server = ApiServer(app)
        val port = server.start()
        try {
            val http = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()
            val entry = http.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI(server.entryUrl())).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            )
            val cookie = entry.headers().firstValue("Set-Cookie").get().substringBefore(';')
            fun post(origin: String) = http.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/api/no-such-route"))
                    .header("Cookie", cookie).header("Origin", origin)
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            )
            val same = post("http://127.0.0.1:$port")
            assertEquals(404, same.statusCode(), same.body())
            assertTrue(same.body().contains("\"error\""), same.body())
            assertEquals(403, post("http://evil.example").statusCode())
        } finally {
            server.stop()
        }
    }
}

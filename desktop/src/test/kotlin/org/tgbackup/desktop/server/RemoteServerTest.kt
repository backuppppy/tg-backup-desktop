package org.tgbackup.desktop.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tgbackup.desktop.remote.RemoteAuth
import java.net.BindException
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Adds `Host: 127.0.0.1:47800` unless the request sets its own. */
private val RemoteHost = createClientPlugin("RemoteHost") {
    onRequest { request, _ ->
        if (!request.headers.contains(HttpHeaders.Host)) request.headers.append(HttpHeaders.Host, "127.0.0.1:$REMOTE_PORT")
    }
}

private const val TAILNET_NAME = "pc.tail1234.ts.net"
private const val PASSWORD = "secret-pw-1"

class RemoteServerTest : RouteTestBase() {
    private val auth = RemoteAuth(dataDir.toFile()).also { it.setPassword(PASSWORD) }

    @AfterTest
    fun tearDown() = closeApp()

    private fun withRemote(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application {
            remoteModule(app = app, auth = auth, allowedNames = { setOf(TAILNET_NAME) }, handles = handles, locks = locks)
        }
        block(
            createClient {
                followRedirects = false
                install(RemoteHost)
            },
        )
    }

    private suspend fun HttpClient.login(password: String = PASSWORD, who: String = "phone@example.com"): HttpResponse =
        post("/api/login") {
            header("Tailscale-User-Login", who)
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$password"}""")
        }

    /** The `tgb_remote` value of a login's Set-Cookie. */
    private fun HttpResponse.session(): String {
        val set = headers.getAll(HttpHeaders.SetCookie).orEmpty().first { it.startsWith("$REMOTE_COOKIE=") }
        return set.substringAfter("=").substringBefore(';')
    }

    private suspend fun HttpClient.getWith(path: String, session: String): HttpResponse =
        get(path) { header(HttpHeaders.Cookie, "$REMOTE_COOKIE=$session") }

    private suspend fun HttpClient.postWith(path: String, session: String, body: String = "{}"): HttpResponse =
        post(path) {
            header(HttpHeaders.Cookie, "$REMOTE_COOKIE=$session")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun withoutACookieTheApiIs401() = withRemote { client ->
        val r = client.get("/api/state")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        val body = TgbJson.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("נדרשת התחברות", body["error"]?.jsonPrimitive?.content)
        assertEquals("true", body["login"]?.jsonPrimitive?.content)
        // The window's cookie means nothing here.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/state") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=t1") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.getWith("/api/state", "not-a-session").status)
        // The public ones.
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
        assertEquals(HttpStatusCode.OK, client.get("/login.html").status)
        assertEquals(HttpStatusCode.OK, client.get("/login.js").status)
        assertEquals(HttpStatusCode.OK, client.get("/app.css").status)
        assertEquals(HttpStatusCode.OK, client.get("/favicon.png").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/app.js").status)
    }

    @Test
    fun theRootRedirectsToLogin() = withRemote { client ->
        val r = client.get("/")
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/login.html", r.headers[HttpHeaders.Location])
    }

    @Test
    fun loginSetsASecureHttpOnlyStrictCookie() = withRemote { client ->
        val r = client.login()
        r.assertOk()
        val set = r.headers.getAll(HttpHeaders.SetCookie).orEmpty().single { it.startsWith("$REMOTE_COOKIE=") }
        val parts = set.split(';').map { it.trim() }
        assertTrue("Secure" in parts, set)
        assertTrue("HttpOnly" in parts, set)
        assertTrue("SameSite=Strict" in parts, set)
        assertTrue("Max-Age=315360000" in parts, set)
        assertTrue("Path=/" in parts, set)
        assertEquals(64, r.session().length)
        assertTrue(auth.validate(r.session()))
    }

    @Test
    fun withoutSecureTheCookieHasNoSecure() = testApplication {
        application { remoteModule(app = app, auth = auth, allowedNames = { emptySet() }, secureCookie = false) }
        val client = createClient { install(RemoteHost) }
        val set = client.login().headers.getAll(HttpHeaders.SetCookie).orEmpty().single { it.startsWith("$REMOTE_COOKIE=") }
        assertFalse(set.split(';').map { it.trim() }.contains("Secure"), set)
    }

    @Test
    fun theCookieOpensTheApi() = withRemote { client ->
        val session = client.login().session()
        assertEquals(HttpStatusCode.OK, client.getWith("/api/state", session).status)
        assertEquals(HttpStatusCode.OK, client.getWith("/api/desktop-prefs", session).status)
        val page = client.getWith("/", session)
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("<nav class=\"tabs\""))
        assertEquals(HttpStatusCode.OK, client.getWith("/app.js", session).status)
    }

    @Test
    fun aWrongHostIs403() = withRemote { client ->
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/health") { header(HttpHeaders.Host, "evil.example:47800") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/health") { header(HttpHeaders.Host, "127.0.0.1:8765") }.status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/api/health") { header("X-Forwarded-Host", "evil.example") }.status,
        )
        // The tailnet name, with or without :443, and loopback on the remote port.
        assertEquals(HttpStatusCode.OK, client.get("/api/health") { header(HttpHeaders.Host, TAILNET_NAME) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/health") { header(HttpHeaders.Host, "$TAILNET_NAME:443") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/health") { header(HttpHeaders.Host, "localhost:$REMOTE_PORT") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/health") { header("X-Forwarded-Host", TAILNET_NAME) }.status)
    }

    @Test
    fun aForeignOriginIs403() = withRemote { client ->
        suspend fun loginFrom(origin: String) = client.post("/api/login") {
            header(HttpHeaders.Origin, origin)
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$PASSWORD"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, loginFrom("https://evil.example").status)
        assertEquals(HttpStatusCode.Forbidden, loginFrom("http://$TAILNET_NAME").status)
        assertEquals(HttpStatusCode.Forbidden, loginFrom("http://127.0.0.1:8765").status)
        assertEquals(HttpStatusCode.OK, loginFrom("https://$TAILNET_NAME").status)
        assertEquals(HttpStatusCode.OK, loginFrom("http://127.0.0.1:$REMOTE_PORT").status)
    }

    @Test
    fun internalShowIsNotThere() = withRemote { client ->
        val session = client.login().session()
        client.post(INTERNAL_SHOW_PATH).assertError(HttpStatusCode.NotFound, "לא נמצא")
        client.postWith(INTERNAL_SHOW_PATH, session).assertError(HttpStatusCode.NotFound, "לא נמצא")
    }

    @Test
    fun exportOpenIsRefusedRemotely() {
        withRemote { client ->
            val session = client.login().session()
            client.postWith("/api/export/open", session).assertError(HttpStatusCode.Forbidden, "זמין רק במחשב")
        }
        withServer { client ->
            client.postJson("/api/export/open").assertError(HttpStatusCode.Conflict, "אין ייצוא שהסתיים")
        }
    }

    @Test
    fun snapshotSaysRemote() {
        withRemote { client ->
            val session = client.login().session()
            val body = TgbJson.parseToJsonElement(client.getWith("/api/state", session).bodyAsText()).jsonObject
            assertEquals("remote", body["viewer"]?.jsonPrimitive?.content)
        }
        withServer { client ->
            val body = TgbJson.parseToJsonElement(client.get("/api/state").bodyAsText()).jsonObject
            assertEquals("local", body["viewer"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun logoutEndsOnlyThisSession() = withRemote { client ->
        val a = client.login().session()
        val b = client.login().session()
        val out = client.postWith("/api/logout", a)
        out.assertOk()
        val cleared = out.headers.getAll(HttpHeaders.SetCookie).orEmpty().single { it.startsWith("$REMOTE_COOKIE=") }
        assertTrue(cleared.startsWith("$REMOTE_COOKIE=;"), cleared)
        assertTrue(cleared.contains("Max-Age=0"), cleared)
        assertEquals(HttpStatusCode.Unauthorized, client.getWith("/api/state", a).status)
        assertEquals(HttpStatusCode.OK, client.getWith("/api/state", b).status)
        assertEquals(1, auth.devices.value)
    }

    @Test
    fun theWaitIs429WithSeconds() = withRemote { client ->
        repeat(3) { client.login("bad-pass").assertError(HttpStatusCode.Unauthorized, "סיסמה שגויה") }
        val r = client.login("bad-pass")
        assertEquals(HttpStatusCode.TooManyRequests, r.status)
        val body = TgbJson.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("יותר מדי ניסיונות. אפשר לנסות שוב בעוד 2 שניות.", body["error"]?.jsonPrimitive?.content)
        assertEquals("2", body["seconds"]?.jsonPrimitive?.content)
        // Another device (by X-Forwarded-For here) is not held back.
        val other = client.post("/api/login") {
            header("X-Forwarded-For", "100.64.0.7, 10.0.0.1")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$PASSWORD"}""")
        }
        other.assertOk()
    }

    @Test
    fun noLogLineHoldsThePassword() = withRemote { client ->
        client.login("bad-pass-9", who = "phone@example.com").assertError(HttpStatusCode.Unauthorized, "סיסמה שגויה")
        val session = client.login(who = "phone@example.com").session()
        client.post("/api/login") {
            header("X-Forwarded-For", "100.64.0.7, 10.0.0.1")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"bad-pass-8"}""")
        }
        client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"bad-pass-7"}""")
        }
        client.postWith("/api/logout", session)
        val lines = app.log.entries.value.map { it.msg }
        assertTrue("ניסיון כניסה כושל (phone@example.com)" in lines, lines.toString())
        assertTrue("מכשיר התחבר מהטלפון (phone@example.com)" in lines, lines.toString())
        assertTrue("ניסיון כניסה כושל (100.64.0.7)" in lines, lines.toString())
        assertTrue("ניסיון כניסה כושל (לא ידוע)" in lines, lines.toString())
        for (secret in listOf(PASSWORD, "bad-pass-9", "bad-pass-8", "bad-pass-7", session)) {
            assertFalse(lines.any { it.contains(secret) }, "$secret in $lines")
        }
    }

    @Test
    fun aTakenPortThrowsBindException() {
        ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { taken ->
            val server = RemoteServer(app, auth, port = taken.localPort, allowedNames = { emptySet() })
            assertFailsWith<BindException> { server.start() }
            server.stop()
        }
    }

    @Test
    fun theRealServerListensOnLoopback() {
        val port = ServerSocket(0).use { it.localPort }
        val server = RemoteServer(app, auth, port = port, allowedNames = { emptySet() })
        server.start()
        try {
            val conn = java.net.URI("http://127.0.0.1:$port/api/health").toURL().openConnection() as java.net.HttpURLConnection
            assertEquals(200, conn.responseCode)
            assertNotNull(conn.inputStream.readBytes())
        } finally {
            server.stop()
        }
    }
}

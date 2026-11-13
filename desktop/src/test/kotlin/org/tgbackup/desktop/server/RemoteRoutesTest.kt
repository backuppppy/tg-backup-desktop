package org.tgbackup.desktop.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tgbackup.desktop.remote.TsStatus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val NAME = "pc.tail1234.ts.net"

private val PhoneHost = createClientPlugin("PhoneHost") {
    onRequest { request, _ -> request.headers.append(HttpHeaders.Host, NAME) }
}

class RemoteRoutesTest : RouteTestBase() {
    @AfterTest
    fun tearDown() = closeApp()

    private suspend fun HttpResponse.json() = TgbJson.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun theStatusStartsOff() = withServer { client ->
        val s = client.get("/api/remote").json()
        assertEquals("Off", s["state"]!!.jsonPrimitive.content)
        assertFalse(s["enabled"]!!.jsonPrimitive.boolean)
        assertFalse(s["hasPassword"]!!.jsonPrimitive.boolean)
        assertEquals(0, s["devices"]!!.jsonPrimitive.int)
        // In the snapshot too.
        assertEquals("Off", client.get("/api/state").json()["remote"]!!.jsonObject["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun enablingWithoutAPasswordIs409() = withServer { client ->
        client.putJson("/api/remote/enabled", """{"enabled":true}""").assertError(HttpStatusCode.Conflict, "קודם קובעים סיסמה")
        assertFalse(app.prefs.value.value.remoteAccess)
    }

    @Test
    fun aPasswordThenTheSwitch() = withServer { client ->
        client.putJson("/api/remote/password", """{"password":"12345"}""").assertError(HttpStatusCode.BadRequest, "לפחות 6 תווים")
        client.putJson("/api/remote/password", """{"password":"secret1"}""").assertOk()
        assertTrue(app.remoteAuth.hasPassword())
        withTimeout(5.seconds) { app.remote.status.first { it.hasPassword } }
        client.putJson("/api/remote/enabled", """{"enabled":true}""").assertOk()
        assertTrue(app.prefs.value.value.remoteAccess)
        client.putJson("/api/remote/enabled", """{"enabled":false}""").assertOk()
        assertFalse(app.prefs.value.value.remoteAccess)
    }

    @Test
    fun disconnectAllEndsEverySession() = withServer { client ->
        app.remoteAuth.setPassword("secret1")
        app.remoteAuth.login("secret1", "a")
        assertEquals(1, app.remoteAuth.devices.value)
        client.postJson("/api/remote/disconnect-all").assertOk()
        assertEquals(0, app.remoteAuth.devices.value)
        assertTrue("כל המכשירים נותקו" in app.log.entries.value.map { it.msg })
    }

    @Test
    fun aPhoneMayReadButNotChange() = testApplication {
        app.remoteAuth.setPassword("secret1")
        application {
            remoteModule(app = app, auth = app.remoteAuth, allowedNames = { setOf(NAME) }, handles = handles, locks = locks)
        }
        val client = createClient { install(PhoneHost) }
        val login = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"secret1"}""")
        }
        val session = login.headers.getAll(HttpHeaders.SetCookie)!!.first { it.startsWith("$REMOTE_COOKIE=") }
            .substringAfter("=").substringBefore(';')
        val cookie = "$REMOTE_COOKIE=$session"

        assertEquals(HttpStatusCode.OK, client.get("/api/remote") { header(HttpHeaders.Cookie, cookie) }.status)
        suspend fun write(r: HttpResponse) = r.assertError(HttpStatusCode.Forbidden, "זמין רק במחשב")
        write(client.put("/api/remote/password") {
            header(HttpHeaders.Cookie, cookie); contentType(ContentType.Application.Json); setBody("""{"password":"other-pw"}""")
        })
        write(client.put("/api/remote/enabled") {
            header(HttpHeaders.Cookie, cookie); contentType(ContentType.Application.Json); setBody("""{"enabled":true}""")
        })
        write(client.post("/api/remote/disconnect-all") { header(HttpHeaders.Cookie, cookie) })
        assertFalse(app.prefs.value.value.remoteAccess)
        assertEquals(1, app.remoteAuth.devices.value)
    }

    @Test
    fun theQrIsAnSvgOfTheUrlWhileRunning() = withServer { client ->
        client.get("/api/remote/qr.svg").assertError(HttpStatusCode.NotFound, MSG_NOT_FOUND)
        app.remoteAuth.setPassword("secret1")
        tailscale.status = TsStatus.Up(NAME, httpsOn = true)
        app.remote.start()
        assertTrue(app.remote.setEnabled(true))
        withTimeout(5.seconds) { app.remote.status.first { it.state == "Running" } }
        val r = client.get("/api/remote/qr.svg")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(ContentType.Image.SVG, r.contentType()?.withoutParameters())
        assertTrue("<svg" in r.bodyAsText())
        assertEquals("https://$NAME", client.get("/api/remote").json()["url"]!!.jsonPrimitive.content)
    }
}

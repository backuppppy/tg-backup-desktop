package org.tgbackup.desktop.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import org.tgbackup.app.data.Account
import org.tgbackup.desktop.DesktopApp
import org.tgbackup.desktop.PowerRequests
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

private val RouteDefaults = createClientPlugin("RouteDefaults") {
    onRequest { request, _ ->
        if (!request.headers.contains(HttpHeaders.Host)) request.headers.append(HttpHeaders.Host, "localhost:80")
        if (!request.headers.contains(HttpHeaders.Cookie)) request.headers.append(HttpHeaders.Cookie, "$SESSION_COOKIE=t1")
    }
}

/** A [DesktopApp] under a Hebrew dir with a space, plus helpers for the command routes. */
abstract class RouteTestBase {
    protected val dataDir: Path = Files.createTempDirectory("routes-test").resolve("תיקייה עם רווח")
    protected val app = DesktopApp(dataDir, PowerRequests { })

    /** The dummy account: valid enough for a TDLib client, reaches NeedPhone, never logs in. */
    protected fun dummy(id: String, name: String = "") =
        Account(id = id, name = name, apiId = 1, apiHash = "0".repeat(32), phone = "+10000000000")

    protected fun closeApp() {
        app.jobs.stopAll()
        app.close()
    }

    protected fun withServer(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { tgbModule(app = app, token = "t1", allowedHosts = setOf("localhost:80")) }
        block(createClient { install(RouteDefaults) })
    }

    protected suspend fun HttpClient.postJson(path: String, body: String = "{}"): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    protected suspend fun HttpClient.putJson(path: String, body: String): HttpResponse = put(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    protected suspend fun HttpClient.del(path: String): HttpResponse = delete(path)

    protected suspend fun HttpResponse.assertOk() {
        val text = bodyAsText()
        assertEquals(HttpStatusCode.OK, status, text)
        assertEquals("true", TgbJson.parseToJsonElement(text).jsonObject["ok"]?.jsonPrimitive?.content, text)
    }

    protected suspend fun HttpResponse.assertError(expected: HttpStatusCode, message: String) {
        val text = bodyAsText()
        assertEquals(expected, status, text)
        assertEquals(message, TgbJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content, text)
    }
}

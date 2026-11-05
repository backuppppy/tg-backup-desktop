package org.tgbackup.desktop.server

import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.tgbackup.desktop.devExtraRoutes
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** `POST /api/dev/export-state` exists only on the DevServer with TGB_DEV_FIXTURE set, never on ApiServer itself. */
class DevRoutesTest : RouteTestBase() {
    @AfterTest
    fun tearDown() = closeApp()

    private val finished = """{"accountId":"a1","title":"T","state":{"type":"Finished","path":"/x","filesCount":2,"bytesCount":5}}"""

    private fun post(server: ApiServer, port: Int, body: String): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/dev/export-state"))
                .header("Cookie", "$SESSION_COOKIE=${server.sessionToken}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun apiServerHasNoDevRoute() {
        val server = ApiServer(app)
        val port = server.start()
        try {
            val r = post(server, port, finished)
            assertEquals(404, r.statusCode(), r.body())
        } finally {
            server.stop()
        }
    }

    @Test
    fun theModuleWithoutExtraRoutesHasNoDevRoute() = withServer { client ->
        client.postJson("/api/dev/export-state", finished).assertError(HttpStatusCode.NotFound, MSG_NOT_FOUND)
    }

    @Test
    fun noFixtureMeansNoDevRoute() {
        for (fixture in listOf(null, "", "  ")) {
            val server = ApiServer(app, extraRoutes = devExtraRoutes(fixture))
            val port = server.start()
            try {
                assertEquals(404, post(server, port, finished).statusCode(), "fixture=$fixture")
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun theDevRoutePublishesTheExportEvent() = testApplication {
        application {
            tgbModule(app = app, token = "t1", allowedHosts = setOf("localhost:80"), extraRoutes = devExtraRoutes("1"))
        }
        val client = createClient { install(RouteDefaultsForTests) }
        client.prepareGet("/api/events").execute { r ->
            val ch = r.bodyAsChannel()
            withTimeout(20.seconds) {
                // The snapshot first: the stream is subscribed before the event is published.
                while (ch.readUTF8Line() != "event: snapshot") Unit
                val posted = async { client.postJson("/api/dev/export-state", finished) }
                posted.await().assertOk()
                while (ch.readUTF8Line() != "event: export") Unit
                val data = ch.readUTF8Line()!!.removePrefix("data: ")
                assertEquals(TgbJson.parseToJsonElement(finished), TgbJson.parseToJsonElement(data))

                client.postJson("/api/dev/export-state", "null").assertOk()
                while (ch.readUTF8Line() != "event: export") delay(1)
                assertEquals("data: null", ch.readUTF8Line())
            }
        }
    }

    @Test
    fun aBadBodyIsRefused() = testApplication {
        application {
            tgbModule(app = app, token = "t1", allowedHosts = setOf("localhost:80"), extraRoutes = devExtraRoutes("1"))
        }
        val client = createClient { install(RouteDefaultsForTests) }
        client.postJson("/api/dev/export-state", """{"accountId":"a"}""").assertError(HttpStatusCode.BadRequest, MSG_BAD_REQUEST)
    }
}

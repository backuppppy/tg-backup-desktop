package org.tgbackup.desktop.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.tgbackup.app.BuildConfig
import org.tgbackup.app.data.Account
import org.tgbackup.app.data.Job
import org.tgbackup.app.data.Progress
import org.tgbackup.app.engine.Level
import org.tgbackup.desktop.DesktopApp
import org.tgbackup.desktop.PowerRequests
import java.net.Socket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val DefaultHost = createClientPlugin("DefaultHostEvents") {
    onRequest { request, _ ->
        if (!request.headers.contains(HttpHeaders.Host)) request.headers.append(HttpHeaders.Host, "localhost:80")
        if (!request.headers.contains(HttpHeaders.Cookie)) request.headers.append(HttpHeaders.Cookie, "$SESSION_COOKIE=t1")
    }
}

/** Minimal text/event-stream parser: returns (event, data) and skips comment lines. */
private class SseReader(private val ch: ByteReadChannel) {
    val comments = mutableListOf<String>()

    suspend fun next(): Pair<String, JsonElement> {
        var event = "message"
        val data = StringBuilder()
        while (true) {
            val line = ch.readUTF8Line() ?: error("stream ended")
            when {
                line.isEmpty() -> if (data.isNotEmpty()) return event to TgbJson.parseToJsonElement(data.toString())
                line.startsWith(":") -> comments += line
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
            }
        }
    }

    /** Skips events until one named [name]. */
    suspend fun nextNamed(name: String): JsonElement {
        while (true) {
            val (e, d) = next()
            if (e == name) return d
        }
    }
}

class EventsTest {
    private val app = DesktopApp(Files.createTempDirectory("events-test"), PowerRequests { })

    @AfterTest
    fun closeApp() = app.close()

    private lateinit var module: Application

    private fun withServer(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) =
        testApplication {
            application {
                tgbModule(app = app, token = "t1", allowedHosts = setOf("localhost:80"))
                module = this
            }
            block(createClient { install(DefaultHost) })
        }

    private suspend fun HttpClient.events(block: suspend (SseReader) -> Unit) =
        prepareGet("/api/events").execute { r ->
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals(ContentType.Text.EventStream, r.contentType()?.withoutParameters())
            withTimeout(20.seconds) { block(SseReader(r.bodyAsChannel())) }
        }

    @Test
    fun stateReturnsSnapshotWithoutSecrets() = withServer { client ->
        val hash = "fedcba9876543210fedcba9876543210"
        app.store.updateConfig { it.copy(accounts = listOf(Account(id = "a1", apiId = 7, apiHash = hash, phone = "+1"))) }
        val r = client.get("/api/state")
        assertEquals(HttpStatusCode.OK, r.status)
        val text = r.bodyAsText()
        assertFalse(text.contains(hash), text)
        val o = TgbJson.parseToJsonElement(text).jsonObject
        assertEquals(BuildConfig.VERSION_NAME, o["version"]!!.jsonPrimitive.content)
        assertTrue(o["tdlibVersion"]!!.jsonPrimitive.content.isNotEmpty())
        val acc = o["accounts"]!!.jsonArray.single().jsonObject
        assertTrue(acc["hasApiHash"]!!.jsonPrimitive.boolean)
        val st = o["accountStates"]!!.jsonArray.single().jsonObject
        assertEquals("a1", st["id"]!!.jsonPrimitive.content)
        assertEquals("Unsupported", st["auth"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("חסרים API ID / Hash", st["auth"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertTrue(o.containsKey("exportPrefs"))
        assertTrue(o["exportPrefs"]!!.jsonObject["defaultPath"]!!.jsonPrimitive.content.isNotEmpty())
    }

    @Test
    fun eventsNeedTheCookie() = withServer { client ->
        val r = client.get("/api/events") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=wrong") }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun firstEventIsSnapshot() = withServer { client ->
        app.log.info("לפני")
        client.events { sse ->
            val (name, data) = sse.next()
            assertEquals("snapshot", name)
            val o = data.jsonObject
            assertEquals(BuildConfig.VERSION_NAME, o["version"]!!.jsonPrimitive.content)
            assertEquals("לפני", o["log"]!!.jsonArray.last().jsonObject["msg"]!!.jsonPrimitive.content)
            for (k in listOf("accounts", "accountStates", "jobs", "statuses", "exportPrefs")) assertTrue(o.containsKey(k), k)
        }
    }

    /** The page's run clock follows the server's clock: the snapshot and every statuses event carry the server's now. */
    @Test
    fun snapshotAndStatusesCarryTheServerNow() = withServer { client ->
        val before = System.currentTimeMillis()
        val state = TgbJson.parseToJsonElement(client.get("/api/state").bodyAsText()).jsonObject
        val stateNow = state["serverNow"]!!.jsonPrimitive.long
        assertTrue(stateNow in before..System.currentTimeMillis(), "$stateNow")
        client.events { sse ->
            val (name, data) = sse.next()
            assertEquals("snapshot", name)
            val snapNow = data.jsonObject["serverNow"]!!.jsonPrimitive.long
            assertTrue(snapNow >= stateNow, "$snapNow")
            app.store.updateConfig { it.copy(jobs = it.jobs + Job(id = "j1", name = "משימה")) }
            app.store.saveProgress("j1") { Progress(lastId = 10, copiedTotal = 3, runMillis = 5000) }
            val sent = System.currentTimeMillis()
            app.jobs.showSavedProgress("j1")
            val ev = sse.nextNamed("statuses").jsonObject
            val evNow = ev["serverNow"]!!.jsonPrimitive.long
            assertTrue(evNow in sent..System.currentTimeMillis(), "$evNow")
            val j1 = ev["statuses"]!!.jsonObject["j1"]!!.jsonObject
            assertEquals(3L, j1["copiedTotal"]!!.jsonPrimitive.long)
            assertEquals(5000L, j1["runMillis"]!!.jsonPrimitive.long)
        }
    }

    @Test
    fun twoPasswordRequestsGoOutAsTheListAndItsOldest() = withServer { client ->
        client.events { sse ->
            assertEquals("snapshot", sse.next().first)
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            try {
                scope.launch { app.exports.askPassword("a1", org.tgbackup.app.export.PasswordOrigin.Job, "h1") }
                withTimeout(5_000) { app.exports.passwords.first { it.size == 1 } }
                scope.launch { app.exports.askPassword("a2", org.tgbackup.app.export.PasswordOrigin.Screen, "h2") }
                var list = sse.nextNamed("passwords").jsonArray
                while (list.size < 2) list = sse.nextNamed("passwords").jsonArray
                assertEquals(listOf("job", "screen"), list.map { it.jsonObject["origin"]!!.jsonPrimitive.content })
                assertEquals(listOf("a1", "a2"), list.map { it.jsonObject["accountId"]!!.jsonPrimitive.content })
                // `password`, the oldest, follows each list.
                val first = sse.nextNamed("password").jsonObject
                assertEquals(list[0].jsonObject["id"], first["id"])
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun configChangeSendsConfigEvent() = withServer { client ->
        client.events { sse ->
            assertEquals("snapshot", sse.next().first)
            app.store.updateConfig { it.copy(jobs = it.jobs + Job(id = "j1", name = "משימה")) }
            val cfg = sse.nextNamed("config").jsonObject
            val job = cfg["jobs"]!!.jsonArray.single().jsonObject
            assertEquals("j1", job["id"]!!.jsonPrimitive.content)
            assertEquals("משימה", job["name"]!!.jsonPrimitive.content)
            assertTrue(cfg.containsKey("accounts"))
        }
    }

    @Test
    fun desktopPrefsChangeSendsItsEvent() = withServer { client ->
        client.events { sse ->
            val (name, snap) = sse.next()
            assertEquals("snapshot", name)
            assertEquals(true, snap.jsonObject["desktopPrefs"]!!.jsonObject["keepAwake"]!!.jsonPrimitive.boolean)
            app.prefs.update { it.copy(keepAwake = false) }
            val prefs = sse.nextNamed("desktopPrefs").jsonObject
            assertEquals(false, prefs["keepAwake"]!!.jsonPrimitive.boolean)
            assertEquals(false, prefs["autostart"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun logIsIncremental() = withServer { client ->
        client.events { sse ->
            assertEquals("snapshot", sse.next().first)
            app.log.info("a")
            val first = sse.nextNamed("log").jsonObject
            app.log.info("b")
            val second = sse.nextNamed("log").jsonObject
            val e1 = first["entries"]!!.jsonArray.single().jsonObject
            val e2 = second["entries"]!!.jsonArray.single().jsonObject
            assertEquals("a", e1["msg"]!!.jsonPrimitive.content)
            assertEquals("b", e2["msg"]!!.jsonPrimitive.content)
            assertEquals("INFO", e1["level"]!!.jsonPrimitive.content)
            assertTrue(e2["seq"]!!.jsonPrimitive.long > e1["seq"]!!.jsonPrimitive.long)
            assertFalse(first["cleared"]!!.jsonPrimitive.boolean)
            assertFalse(second["cleared"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun logClearSendsCleared() = withServer { client ->
        app.log.info("ישן")
        client.events { sse ->
            val snap = sse.next()
            assertEquals("snapshot", snap.first)
            assertEquals(1, snap.second.jsonObject["log"]!!.jsonArray.size)
            app.log.clear()
            val cleared = sse.nextNamed("log").jsonObject
            assertTrue(cleared["cleared"]!!.jsonPrimitive.boolean)
            assertEquals(0, cleared["entries"]!!.jsonArray.size)
            // A clear followed at once by a new line still reads as a clear.
            app.log.info("x")
            val next = sse.nextNamed("log").jsonObject
            assertFalse(next["cleared"]!!.jsonPrimitive.boolean)
            assertEquals("x", next["entries"]!!.jsonArray.single().jsonObject["msg"]!!.jsonPrimitive.content)
            app.log.clear()
            app.log.info("y")
            // Either one event (cleared, [y]) or two ((cleared, []) then [y]): a clear is never lost.
            var sawClear = false
            while (true) {
                val e = sse.nextNamed("log").jsonObject
                sawClear = sawClear || e["cleared"]!!.jsonPrimitive.boolean
                val msgs = e["entries"]!!.jsonArray.map { it.jsonObject["msg"]!!.jsonPrimitive.content }
                assertFalse("x" in msgs, msgs.toString())
                if ("y" in msgs) break
            }
            assertTrue(sawClear)
        }
    }

    @Test
    fun publishedEventReachesTheStream() = withServer { client ->
        client.events { sse ->
            assertEquals("snapshot", sse.next().first)
            module.attributes[EventsKey].publish("chatsProgress", buildJsonObject { put("count", 3) })
            val d = sse.nextNamed("chatsProgress") as JsonObject
            assertEquals(3L, d["count"]!!.jsonPrimitive.long)
        }
    }

    @Test
    fun disconnectCancelsTheConnectionQuietly() = withServer { client ->
        client.events { sse -> assertEquals("snapshot", sse.next().first) }
        val events = module.attributes[EventsKey]
        // The next write to the closed connection fails; the connection's flows must stop, with no error logged.
        withTimeout(20.seconds) {
            while (events.connections.value > 0) {
                events.publish("ping", buildJsonObject { put("n", 1) })
                app.log.info("tick")
                delay(100)
            }
        }
        assertEquals(0, events.connections.value)
        val errors = app.log.entries.value.filter { it.level == Level.ERROR }
        assertTrue(errors.isEmpty(), errors.toString())
    }

    /** The test engine hides it: on real CIO a write to a closed connection throws ChannelWriteException. */
    @Test
    fun disconnectOnTheRealServerLogsNoError() {
        val server = ApiServer(app)
        val port = server.start()
        try {
            Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().write(
                    ("GET /api/events HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                        "Cookie: $SESSION_COOKIE=${server.sessionToken}\r\n\r\n").toByteArray(),
                )
                val head = s.getInputStream().bufferedReader().readLine()
                assertEquals("HTTP/1.1 200 OK", head)
            }
            val events = server.events()
            runBlocking {
                withTimeout(20.seconds) {
                    while (events.connections.value > 0) {
                        app.log.info("tick")
                        delay(100)
                    }
                }
            }
            Thread.sleep(300)
            val errors = app.log.entries.value.filter { it.level == Level.ERROR }
            assertTrue(errors.isEmpty(), errors.toString())
        } finally {
            server.stop()
        }
    }
}

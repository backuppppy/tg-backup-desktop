package org.tgbackup.desktop.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tgbackup.app.data.Job
import org.tgbackup.app.data.JobMode
import org.tgbackup.app.data.MediaType
import org.tgbackup.app.data.Progress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobRoutesTest : RouteTestBase() {

    @AfterTest
    fun tearDown() = closeApp()

    private fun seed(vararg jobs: Job) {
        app.store.updateConfig { it.copy(accounts = listOf(dummy("a1", "אחד"), dummy("a2")), jobs = jobs.toList()) }
    }

    private val j1 = Job(id = "j1", name = "משימה", source = "@src", target = "@dst", accountIds = listOf("a1"))

    @Test
    fun createJobValidates() = withServer { client ->
        seed()
        client.postJson("/api/jobs", """{"source":"  ","target":"@t","accountIds":["a1"]}""")
            .assertError(HttpStatusCode.BadRequest, "חסר מקור או יעד")
        client.postJson("/api/jobs", """{"source":"@s","target":"","accountIds":["a1"]}""")
            .assertError(HttpStatusCode.BadRequest, "חסר מקור או יעד")
        client.postJson("/api/jobs", """{"source":"@s","target":"@t","accountIds":[]}""")
            .assertError(HttpStatusCode.BadRequest, "לא נבחרו חשבונות למשימה")
        // Unknown account ids are dropped, like the dialog that can only tick configured accounts.
        client.postJson("/api/jobs", """{"source":"@s","target":"@t","accountIds":["nope"]}""")
            .assertError(HttpStatusCode.BadRequest, "לא נבחרו חשבונות למשימה")
        assertTrue(app.store.config.value.jobs.isEmpty())
    }

    @Test
    fun createJobPersists() = withServer { client ->
        seed()
        client.postJson(
            "/api/jobs",
            """{"name":" שם ","source":" @s ","target":"-100123","accountIds":["a2","a1"],"mediaType":"ALL","extra":1}""",
        ).assertOk()
        val job = app.store.config.value.jobs.single()
        assertEquals(8, job.id.length)
        assertEquals("שם", job.name)
        assertEquals("@s", job.source)
        assertEquals("-100123", job.target)
        assertEquals(listOf("a1", "a2"), job.accountIds) // config order, like the dialog
        assertEquals(MediaType.ALL, job.mediaType)
        assertEquals(JobMode.ORDERED, job.mode)
        assertEquals(25, job.perMinuteLimit)
        assertTrue(job.stripLinks)
        assertFalse(job.viaTakeout)
    }

    @Test
    fun accountTakenByOtherJobIs400() = withServer { client ->
        seed(j1)
        client.postJson("/api/jobs", """{"source":"@s","target":"@t","accountIds":["a1","a2"]}""")
            .assertError(HttpStatusCode.BadRequest, "החשבון אחד כבר עובד במשימה אחרת")
        // Blank names fall back to the phone.
        app.store.updateConfig { it.copy(jobs = it.jobs + Job(id = "j2", source = "@a", target = "@b", accountIds = listOf("a2"))) }
        client.putJson("/api/jobs/j1", """{"source":"@s","target":"@t","accountIds":["a1","a2"]}""")
            .assertError(HttpStatusCode.BadRequest, "החשבון +10000000000 כבר עובד במשימה אחרת")
        // The job's own accounts are not "taken".
        client.putJson("/api/jobs/j1", """{"name":"חדש","source":"@s","target":"@t","accountIds":["a1"],"perMinuteLimit":0}""")
            .assertOk()
        val saved = app.store.config.value.jobs.first { it.id == "j1" }
        assertEquals("חדש", saved.name)
        assertEquals(0, saved.perMinuteLimit)
    }

    @Test
    fun editUnknownJobIs404() = withServer { client ->
        seed()
        client.putJson("/api/jobs/zz", """{"source":"@s","target":"@t","accountIds":["a1"]}""")
            .assertError(HttpStatusCode.NotFound, MSG_NOT_FOUND)
        for (path in listOf("zz/start", "zz/stop", "zz/pause", "zz/resume", "zz/reset", "zz/latest", "all")) {
            client.postJson("/api/jobs/$path").assertError(HttpStatusCode.NotFound, MSG_NOT_FOUND)
        }
    }

    @Test
    fun createIgnoresBodyId() = withServer { client ->
        seed(j1)
        client.postJson("/api/jobs", """{"id":"../x","source":"@s","target":"@t","accountIds":["a2"]}""").assertOk()
        client.postJson("/api/jobs", """{"id":"j1","source":"@s2","target":"@t2","accountIds":[]}""")
            .assertError(HttpStatusCode.BadRequest, "לא נבחרו חשבונות למשימה")
        val ids = app.store.config.value.jobs.map { it.id }
        assertEquals(2, ids.size)
        assertEquals("j1", ids[0])
        assertTrue(Regex("[0-9a-f-]{8}").matches(ids[1]), ids[1])
        assertEquals("@src", app.store.config.value.jobs[0].source) // j1 was not replaced
    }

    @Test
    fun traversalIdsAre404() = withServer { client ->
        seed(j1)
        val sentinel = dataDir.parent.resolve("sentinel.json")
        java.nio.file.Files.writeString(sentinel, "keep")
        java.nio.file.Files.writeString(dataDir.resolve("sentinel.json"), "keep")
        for (raw in listOf("%2E%2E", "..%2F..", "..%2Fsentinel", "a%2F..%2F..%2Fsentinel", "x".repeat(33))) {
            client.del("/api/jobs/$raw").assertError(HttpStatusCode.NotFound, MSG_NOT_FOUND)
            client.postJson("/api/jobs/$raw/reset").assertError(HttpStatusCode.NotFound, MSG_NOT_FOUND)
            client.postJson("/api/jobs/$raw/latest").assertError(HttpStatusCode.NotFound, MSG_NOT_FOUND)
        }
        assertTrue(java.nio.file.Files.exists(sentinel))
        assertTrue(java.nio.file.Files.exists(dataDir.resolve("sentinel.json")))
        assertEquals(listOf("j1"), app.store.config.value.jobs.map { it.id })
    }

    @Test
    fun secondStartSaysAlreadyRunning() = withServer { client ->
        seed(j1)
        val results = coroutineScope {
            List(2) { async { client.postJson("/api/jobs/j1/start").let { it.status to it.bodyAsText() } } }.awaitAll()
        }
        assertEquals(listOf(HttpStatusCode.Conflict, HttpStatusCode.OK), results.map { it.first }.sortedBy { it.value }.reversed())
        val conflict = results.first { it.first == HttpStatusCode.Conflict }.second
        assertEquals("המשימה כבר פועלת", TgbJson.parseToJsonElement(conflict).jsonObject["error"]!!.jsonPrimitive.content)
        assertTrue(app.jobs.isRunning("j1"))
        client.postJson("/api/jobs/j1/stop").assertOk()
        assertFalse(app.jobs.isRunning("j1"))
    }

    /**
     * The same race on a real server, whose CIO engine handles the two requests on different threads (testApplication
     * may run them one after the other). Each round fires both starts after a latch.
     */
    @Test
    fun parallelStartOnRealServer() {
        seed(j1)
        val server = ApiServer(app)
        val port = server.start()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            val entry = http.send(HttpRequest.newBuilder(URI(server.entryUrl())).build(), HttpResponse.BodyHandlers.discarding())
            val cookie = entry.headers().firstValue("Set-Cookie").get().substringBefore(';')
            fun post(path: String) = http.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header("Cookie", cookie)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            repeat(25) { round ->
                val go = CountDownLatch(1)
                val calls = List(2) { pool.submit(Callable { go.await(); post("/api/jobs/j1/start") }) }
                go.countDown()
                val replies = calls.map { it.get(30, TimeUnit.SECONDS) }
                assertEquals(listOf(200, 409), replies.map { it.statusCode() }.sorted(), "round $round: ${replies.map { it.body() }}")
                assertTrue(replies.first { it.statusCode() == 409 }.body().contains("המשימה כבר פועלת"))
                assertEquals(200, post("/api/jobs/j1/stop").statusCode())
                assertFalse(app.jobs.isRunning("j1"))
            }
        } finally {
            pool.shutdownNow()
            server.stop()
        }
    }

    @Test
    fun startErrorIs409WithEngineText() = withServer { client ->
        seed(j1.copy(accountIds = emptyList()))
        client.postJson("/api/jobs/j1/start").assertError(HttpStatusCode.Conflict, "לא נבחרו חשבונות למשימה")
        client.postJson("/api/jobs/zz/start").assertError(HttpStatusCode.NotFound, MSG_NOT_FOUND)
    }

    @Test
    fun editRunningJobIs409() = withServer { client ->
        seed(j1)
        client.postJson("/api/jobs/j1/start").assertOk()
        client.putJson("/api/jobs/j1", """{"name":"x","source":"@s","target":"@t","accountIds":["a1"]}""")
            .assertError(HttpStatusCode.Conflict, "לא ניתן לשנות משימה בזמן שהיא פועלת")
        client.postJson("/api/jobs/j1/reset").assertError(HttpStatusCode.Conflict, "לא ניתן לשנות משימה בזמן שהיא פועלת")
        assertEquals("משימה", app.store.config.value.jobs.single().name)
        client.postJson("/api/jobs/j1/pause").assertOk()
        assertTrue(app.jobs.statuses.value["j1"]!!.paused)
        client.postJson("/api/jobs/j1/resume").assertOk()
        assertFalse(app.jobs.statuses.value["j1"]!!.paused)
        client.postJson("/api/jobs/j1/stop").assertOk()
        client.postJson("/api/jobs/j1/reset").assertOk()
    }

    @Test
    fun deleteRunningJobIs409() = withServer { client ->
        seed(j1)
        client.postJson("/api/jobs/j1/start").assertOk()
        client.del("/api/jobs/j1").assertError(HttpStatusCode.Conflict, "לא ניתן לשנות משימה בזמן שהיא פועלת")
        assertEquals(1, app.store.config.value.jobs.size)
        client.postJson("/api/jobs/j1/stop").assertOk()
        client.del("/api/jobs/j1").assertOk()
        assertTrue(app.store.config.value.jobs.isEmpty())
        assertFalse("j1" in app.jobs.statuses.value)
        client.del("/api/jobs/j1").assertError(HttpStatusCode.NotFound, MSG_NOT_FOUND)
    }

    @Test
    fun deleteRightAfterStopLeavesNoProgress() = withServer { client ->
        seed(j1)
        app.store.saveProgress("j1") { Progress(lastId = 50) }
        client.postJson("/api/jobs/j1/start").assertOk()
        client.postJson("/api/jobs/j1/stop").assertOk()
        client.del("/api/jobs/j1").assertOk()
        assertTrue(app.jobs.awaitStopped("j1"), "the stopped run ended")
        assertFalse(dataDir.resolve("progress/j1.json").toFile().exists(), "no orphan progress")
        assertFalse("j1" in app.jobs.statuses.value)

        seed(j1)
        app.store.saveProgress("j1") { Progress(lastId = 50) }
        client.postJson("/api/jobs/j1/start").assertOk()
        client.postJson("/api/jobs/j1/stop").assertOk()
        client.postJson("/api/jobs/j1/reset").assertOk()
        assertTrue(app.jobs.awaitStopped("j1"), "the stopped run ended")
        assertEquals(0L, app.store.progress("j1").lastId)
        assertEquals(0L, app.jobs.statuses.value["j1"]!!.lastId)
    }

    @Test
    fun deleteForgetsTheJobLock() = withServer { client ->
        seed(j1)
        client.postJson("/api/jobs/j1/stop").assertOk()
        assertTrue(locks.hasJobLock("j1"))
        client.del("/api/jobs/j1").assertOk()
        assertFalse(locks.hasJobLock("j1"), "the deleted job's mutex is dropped")
    }

    @Test
    fun allCommands() = withServer { client ->
        seed(j1, Job(id = "j2", name = "", source = "", target = "@t"))
        val r = client.postJson("/api/jobs/all/start")
        assertEquals(HttpStatusCode.OK, r.status)
        val errors = TgbJson.parseToJsonElement(r.bodyAsText()).jsonObject["errors"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("j2: חסר מקור או יעד"), errors)
        assertTrue(app.jobs.isRunning("j1"))
        client.postJson("/api/jobs/all/pause").assertOk()
        assertTrue(app.jobs.statuses.value["j1"]!!.paused)
        client.postJson("/api/jobs/all/resume").assertOk()
        assertFalse(app.jobs.statuses.value["j1"]!!.paused)
        client.postJson("/api/jobs/all/stop").assertOk()
        assertFalse(app.jobs.isRunning("j1"))
    }

    @Test
    fun noStateChangingGet() = withServer { client ->
        seed(j1)
        val r = client.postJson("/api/jobs/j1/start")
        r.assertOk()
        client.postJson("/api/jobs/j1/stop").assertOk()
        // GET on a command path is not routed.
        val g = client.get("/api/jobs/j1/start")
        assertTrue(g.status == HttpStatusCode.NotFound || g.status == HttpStatusCode.MethodNotAllowed, g.status.toString())
        assertFalse(app.jobs.isRunning("j1"))
    }

    @Test
    fun latestWithoutALoggedInAccountAnswersZeroAndKeepsTheSavedId() = withServer { client ->
        seed(j1)
        app.store.saveProgress("j1") { Progress(lastId = 10, latestId = 50) }
        val r = client.postJson("/api/jobs/j1/latest")
        r.assertOk()
        // The dummy account never logs in: nothing is read, nothing changes, the job does not start.
        assertEquals("0", TgbJson.parseToJsonElement(r.bodyAsText()).jsonObject["latestId"]?.jsonPrimitive?.content)
        assertEquals(50L, app.store.progress("j1").latestId)
        assertFalse(app.jobs.isRunning("j1"))
        val g = client.get("/api/jobs/j1/latest")
        assertTrue(g.status == HttpStatusCode.NotFound || g.status == HttpStatusCode.MethodNotAllowed, g.status.toString())
    }

    @Test
    fun editingTheSourceForgetsTheOldSourcesLatestId() = withServer { client ->
        seed(j1)
        app.store.saveProgress("j1") { Progress(lastId = 10, copiedTotal = 3, latestId = 50) }
        client.putJson("/api/jobs/j1", """{"name":"שם אחר","source":"@src","target":"@dst","accountIds":["a1"]}""").assertOk()
        assertEquals(50L, app.store.progress("j1").latestId)
        client.putJson("/api/jobs/j1", """{"source":"@other","target":"@dst","accountIds":["a1"]}""").assertOk()
        val p = app.store.progress("j1")
        assertEquals(0L, p.latestId)
        assertEquals(0L, app.jobs.statuses.value["j1"]!!.latestId)
        assertEquals(3L, p.copiedTotal)
        assertEquals(10L, p.lastId)
    }
}

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
            .assertError(HttpStatusCode.NotFound, "המשימה לא נמצאה")
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

    @Test
    fun startErrorIs409WithEngineText() = withServer { client ->
        seed(j1.copy(accountIds = emptyList()))
        client.postJson("/api/jobs/j1/start").assertError(HttpStatusCode.Conflict, "לא נבחרו חשבונות למשימה")
        client.postJson("/api/jobs/zz/start").assertError(HttpStatusCode.Conflict, "המשימה לא נמצאה")
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
        client.del("/api/jobs/j1").assertError(HttpStatusCode.NotFound, "המשימה לא נמצאה")
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
}

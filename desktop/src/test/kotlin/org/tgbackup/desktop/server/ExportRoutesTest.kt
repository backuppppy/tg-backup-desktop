package org.tgbackup.desktop.server

import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tgbackup.app.export.ExportManager
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExportRoutesTest : RouteTestBase() {

    @AfterTest
    fun tearDown() = closeApp()

    private fun seed() {
        app.store.updateConfig { it.copy(accounts = listOf(dummy("a1", "אחד"), dummy("a2").copy(apiHash = ""))) }
    }

    private suspend fun io.ktor.client.HttpClient.patchJson(path: String, body: String) = patch(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun prefsPatchPersists() = withServer { client ->
        client.patchJson("/api/export/prefs", """{"format":"Json","sizeLimit":${50L * 1024 * 1024},"path":"C:\\יצוא"}""")
            .assertOk()
        // mediaTypes is untouched by a patch that leaves it out.
        assertEquals(1, app.exports.prefs.value.mediaTypes)
        client.patchJson("/api/export/prefs", """{"mediaTypes":3}""").assertOk()
        val reread = ExportManager(dataDir.toFile(), app.accounts, app.log).prefs.value
        assertEquals("Json", reread.format)
        assertEquals(50L * 1024 * 1024, reread.sizeLimit)
        assertEquals(3, reread.mediaTypes)
        assertEquals("C:\\יצוא", reread.path)
    }

    @Test
    fun badFormatIs400() = withServer { client ->
        client.patchJson("/api/export/prefs", """{"format":"Pdf"}""").assertError(HttpStatusCode.BadRequest, "ערך לא תקין")
        client.patchJson("/api/export/prefs", """{"sizeLimit":${1024L * 1024 - 1}}""")
            .assertError(HttpStatusCode.BadRequest, "ערך לא תקין")
        client.patchJson("/api/export/prefs", """{"sizeLimit":${4001L * 1024 * 1024}}""")
            .assertError(HttpStatusCode.BadRequest, "ערך לא תקין")
        client.patchJson("/api/export/prefs", """{"mediaTypes":128}""").assertError(HttpStatusCode.BadRequest, "ערך לא תקין")
        // Nothing was half-saved: a bad field refuses the whole patch.
        client.patchJson("/api/export/prefs", """{"format":"Json","sizeLimit":1}""")
            .assertError(HttpStatusCode.BadRequest, "ערך לא תקין")
        assertEquals("Html", app.exports.prefs.value.format)
        client.patchJson("/api/export/prefs", """{"sizeLimit":${4000L * 1024 * 1024}}""").assertOk()
        client.patchJson("/api/export/prefs", """{"format":"HtmlAndJson"}""").assertOk()
        assertEquals("HtmlAndJson", app.exports.prefs.value.format)
    }

    @Test
    fun startWithUnknownPeerIs400() = withServer { client ->
        seed()
        client.postJson("/api/export/start", """{"accountId":"a1","peerId":12345,"from":0,"till":0}""")
            .assertError(HttpStatusCode.BadRequest, "יש לטעון את הצ'אטים מחדש")
        client.postJson("/api/export/start", """{"accountId":"nope","peerId":12345,"from":0,"till":0}""")
            .assertError(HttpStatusCode.NotFound, "לא נמצא")
        client.postJson("/api/export/start", """{"accountId":"../x","peerId":12345,"from":0,"till":0}""")
            .assertError(HttpStatusCode.NotFound, "לא נמצא")
        assertNull(app.exports.running.value)
    }

    @Test
    fun sessionOfAccountWithoutClientIsFalse() = withServer { client ->
        seed()
        for (id in listOf("a1", "a2")) {
            val res = client.get("/api/export/$id/session")
            val text = res.bodyAsText()
            assertEquals(HttpStatusCode.OK, res.status, text)
            assertEquals("false", TgbJson.parseToJsonElement(text).jsonObject["loggedIn"]?.jsonPrimitive?.content, text)
        }
        client.get("/api/export/nope/session").assertError(HttpStatusCode.NotFound, "לא נמצא")
    }

    @Test
    fun accountCommandsNeedAKnownAccount() = withServer { client ->
        seed()
        for (cmd in listOf("login", "check", "chats")) {
            client.postJson("/api/export/nope/$cmd").assertError(HttpStatusCode.NotFound, "לא נמצא")
        }
        client.postJson("/api/export/nope/topics", """{"peerId":1}""").assertError(HttpStatusCode.NotFound, "לא נמצא")
        // A known account, but no chats loaded yet.
        client.postJson("/api/export/a1/topics", """{"peerId":1}""")
            .assertError(HttpStatusCode.BadRequest, "יש לטעון את הצ'אטים מחדש")
    }

    @Test
    fun loginWithoutTdlibSessionIs400() = withServer { client ->
        seed()
        // a2 has no TDLib client: the engine refuses before any network call.
        client.postJson("/api/export/a2/login").assertError(HttpStatusCode.BadRequest, "ההתחברות נכשלה: החשבון לא מחובר ב-TG Backup")
        client.postJson("/api/export/a2/check").assertError(HttpStatusCode.BadRequest, "בדיקה נכשלה: החשבון לא מחובר ב-TG Backup")
    }

    @Test
    fun passwordWithWrongIdIs409() = withServer { client ->
        client.postJson("/api/export/password", """{"id":"abc","password":"x"}""")
            .assertError(HttpStatusCode.Conflict, "הבקשה כבר טופלה")
        client.postJson("/api/export/password", """{"id":"abc","password":null}""")
            .assertError(HttpStatusCode.Conflict, "הבקשה כבר טופלה")
    }

    @Test
    fun controlsWithoutExportAreHarmless() = withServer { client ->
        for (cmd in listOf("skip", "stop", "dismiss")) client.postJson("/api/export/$cmd").assertOk()
        client.postJson("/api/export/open").assertError(HttpStatusCode.Conflict, "אין ייצוא שהסתיים")
    }

    @Test
    fun openOnHeadlessPcIs400() {
        var opened: File? = null
        val headless = DesktopOpener(supported = { false }, open = { opened = it })
        val refusal = assertFailsWith<Refusal> { runBlocking { headless.open("C:\\יצוא") } }
        assertEquals(HttpStatusCode.BadRequest, refusal.status)
        assertEquals("לא ניתן לפתוח במחשב הזה", refusal.message)
        assertNull(opened)

        val working = DesktopOpener(supported = { true }, open = { opened = it })
        runBlocking { working.open("/x/תיקייה עם רווח") }
        assertEquals(File("/x/תיקייה עם רווח"), opened)

        val failing = DesktopOpener(supported = { true }, open = { throw java.io.IOException("no app") })
        assertFailsWith<Refusal> { runBlocking { failing.open("/x") } }
    }

    @Test
    fun chatHandlesKeepTheLastLoad() = runBlocking {
        val handles = ChatHandles()
        assertNull(handles.chat("a1", 1))
        assertNull(handles.topic("a1", 1, 5))
        handles.putTopics("a1", 1, listOf(org.tgbackup.export.TopicEntry(5, "נושא")))
        assertEquals("נושא", handles.topic("a1", 1, 5)?.title)
        assertNull(handles.topic("a1", 2, 5))
        assertNull(handles.topic("a2", 1, 5))
        // A new chats load drops the old topics.
        handles.putChats("a1", emptyList())
        assertNull(handles.topic("a1", 1, 5))
        handles.putTopics("a1", 1, listOf(org.tgbackup.export.TopicEntry(5, "נושא")))
        handles.forget("a1")
        assertNull(handles.topic("a1", 1, 5))
        // Loads of one account run one at a time.
        var inside = 0
        var maxInside = 0
        kotlinx.coroutines.coroutineScope {
            repeat(3) {
                launch {
                    handles.exclusive("a1") {
                        inside++; maxInside = maxOf(maxInside, inside); kotlinx.coroutines.delay(20); inside--
                    }
                }
            }
        }
        assertEquals(1, maxInside)
        assertFalse(inside != 0)
    }

    @Test
    fun aLoadThatEndsAfterForgetDoesNotPutItsChatsBack() {
        val handles = ChatHandles()
        val gen = handles.generation("a1")
        handles.forget("a1")
        handles.putChats("a1", listOf(chatEntry(1)), since = gen)
        assertNull(handles.chat("a1", 1))
        handles.putTopics("a1", 1, listOf(org.tgbackup.export.TopicEntry(5, "נושא")), since = gen)
        assertNull(handles.topic("a1", 1, 5))
        // A load started after the forget is kept.
        handles.putChats("a1", listOf(chatEntry(1)), since = handles.generation("a1"))
        assertEquals("c1", handles.chat("a1", 1)?.title)
    }

    private fun chatEntry(peerId: Long) = org.tgbackup.export.ChatEntry(
        peerId = peerId, title = "c$peerId", username = "", type = org.tgbackup.export.data.DialogInfo.Type.PublicChannel,
        isForum = false, archived = false, input = org.tgbackup.mtproto.tl.MTPDinputPeerEmpty,
    )

    @Test
    fun anUnansweredPasswordRequestIsCancelledAfterTenMinutes() = kotlinx.coroutines.test.runTest {
        assertEquals(kotlin.time.Duration.parse("10m"), PASSWORD_WAIT)
        val requests = kotlinx.coroutines.flow.MutableStateFlow<org.tgbackup.app.export.PasswordRequest?>(null)
        backgroundScope.boundPasswordWait(requests)
        val first = org.tgbackup.app.export.PasswordRequest("", kotlinx.coroutines.CompletableDeferred())
        requests.value = first
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(PASSWORD_WAIT - kotlin.time.Duration.parse("1s"))
        testScheduler.runCurrent()
        assertFalse(first.answer.isCompleted)
        testScheduler.advanceTimeBy(kotlin.time.Duration.parse("2s"))
        testScheduler.runCurrent()
        assertTrue(first.answer.isCompleted)
        assertNull(first.answer.await())
        // Answered in time: kept as answered; the next request gets its own ten minutes.
        val second = org.tgbackup.app.export.PasswordRequest("", kotlinx.coroutines.CompletableDeferred())
        requests.value = second
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(kotlin.time.Duration.parse("5m"))
        second.answer.complete("pw")
        requests.value = null
        val third = org.tgbackup.app.export.PasswordRequest("", kotlinx.coroutines.CompletableDeferred())
        testScheduler.advanceTimeBy(kotlin.time.Duration.parse("1m"))
        requests.value = third
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(kotlin.time.Duration.parse("9m"))
        testScheduler.runCurrent()
        assertEquals("pw", second.answer.await())
        assertFalse(third.answer.isCompleted)
        testScheduler.advanceTimeBy(kotlin.time.Duration.parse("2m"))
        testScheduler.runCurrent()
        assertNull(third.answer.await())
    }

    @Test
    fun chatsLoadingIsInTheSnapshot() {
        val events = Events(app)
        assertTrue(events.snapshot().chatsLoading.isEmpty())
        events.chatsProgress("a1", 0)
        events.chatsProgress("a1", 200)
        assertEquals(mapOf("a1" to 200), events.snapshot().chatsLoading)
        events.chatsDone("a1")
        assertTrue(events.snapshot().chatsLoading.isEmpty())
    }
}

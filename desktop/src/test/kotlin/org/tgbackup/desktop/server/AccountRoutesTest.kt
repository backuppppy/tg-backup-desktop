package org.tgbackup.desktop.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.tgbackup.app.data.Account
import org.tgbackup.app.data.Job
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountRoutesTest : RouteTestBase() {

    @AfterTest
    fun tearDown() = closeApp()

    private val hash = "fedcba9876543210fedcba9876543210"

    @Test
    fun createAccountPersists() = withServer { client ->
        client.postJson("/api/accounts", """{"name":" שלי ","apiId":"12345","apiHash":" $hash ","phone":" +972 50 ","x":1}""")
            .assertOk()
        val acc = app.store.config.value.accounts.single()
        assertEquals(8, acc.id.length)
        assertEquals("שלי", acc.name)
        assertEquals(12345, acc.apiId)
        assertEquals(hash, acc.apiHash)
        assertEquals("+972 50", acc.phone)
        // A number is accepted too.
        client.postJson("/api/accounts", """{"apiId":7,"apiHash":"$hash","phone":"+1"}""").assertOk()
        assertEquals(7, app.store.config.value.accounts.last().apiId)
    }

    @Test
    fun createIgnoresBodyId() = withServer { client ->
        client.postJson("/api/accounts", """{"id":"../..","apiId":"5","apiHash":"$hash","phone":"+1"}""").assertOk()
        val id = app.store.config.value.accounts.single().id
        assertTrue(Regex("[0-9a-f-]{8}").matches(id), id)
    }

    @Test
    fun traversalIdsAre404() = withServer { client ->
        app.store.updateConfig { it.copy(accounts = listOf(Account(id = "a1", apiId = 0, apiHash = hash, phone = "+1"))) }
        val sentinel = dataDir.parent.resolve("sentinel.txt")
        Files.writeString(sentinel, "keep")
        val inside = Files.createDirectories(dataDir.resolve("export/keep")).resolve("f.txt")
        Files.writeString(inside, "keep")
        for (raw in listOf("..%2F..", "%2E%2E", "..%2Fexport%2Fkeep", "x".repeat(33))) {
            client.del("/api/accounts/$raw").assertError(HttpStatusCode.NotFound, MSG_NOT_FOUND)
            client.postJson("/api/accounts/$raw/logout").assertError(HttpStatusCode.NotFound, MSG_NOT_FOUND)
            client.putJson("/api/accounts/$raw", """{"apiId":"6","apiHash":"$hash","phone":"+2"}""")
                .assertError(HttpStatusCode.NotFound, MSG_NOT_FOUND)
        }
        assertTrue(Files.exists(sentinel))
        assertTrue(Files.exists(inside))
        assertEquals(listOf("a1"), app.store.config.value.accounts.map { it.id })
    }

    @Test
    fun badAccountIs400() = withServer { client ->
        val bad = "פרטי החשבון לא תקינים"
        client.postJson("/api/accounts", """{"apiId":"1","apiHash":"0123456789","phone":"+1"}""")
            .assertError(HttpStatusCode.BadRequest, bad)
        client.postJson("/api/accounts", """{"apiId":"abc","apiHash":"$hash","phone":"+1"}""")
            .assertError(HttpStatusCode.BadRequest, bad)
        client.postJson("/api/accounts", """{"apiId":"-5","apiHash":"$hash","phone":"+1"}""")
            .assertError(HttpStatusCode.BadRequest, bad)
        client.postJson("/api/accounts", """{"apiHash":"$hash","phone":"+1"}""")
            .assertError(HttpStatusCode.BadRequest, bad)
        client.postJson("/api/accounts", """{"apiId":"1","apiHash":"$hash","phone":"  "}""")
            .assertError(HttpStatusCode.BadRequest, bad)
        assertTrue(app.store.config.value.accounts.isEmpty())
    }

    @Test
    fun accountGetHidesHash() = withServer { client ->
        client.postJson("/api/accounts", """{"apiId":"5","apiHash":"$hash","phone":"+1"}""").assertOk()
        val text = client.get("/api/state").bodyAsText()
        assertFalse(text.contains(hash), text)
        assertTrue(text.contains("\"hasApiHash\":true"), text)
    }

    @Test
    fun putWithMaskedHashKeepsHash() = withServer { client ->
        app.store.updateConfig { it.copy(accounts = listOf(Account(id = "a1", name = "ישן", apiId = 5, apiHash = hash, phone = "+1"))) }
        client.putJson("/api/accounts/a1", """{"name":"חדש","apiId":"6","apiHash":"••••","phone":"+2"}""").assertOk()
        assertEquals(Account(id = "a1", name = "חדש", apiId = 6, apiHash = hash, phone = "+2"), app.store.config.value.accounts.single())
        val other = "a".repeat(16)
        client.putJson("/api/accounts/a1", """{"name":"חדש","apiId":"6","apiHash":"$other","phone":"+2"}""").assertOk()
        assertEquals(other, app.store.config.value.accounts.single().apiHash)
        client.putJson("/api/accounts/a1", """{"apiId":"6","apiHash":"short","phone":"+2"}""")
            .assertError(HttpStatusCode.BadRequest, "פרטי החשבון לא תקינים")
        client.putJson("/api/accounts/zz", """{"apiId":"6","apiHash":"••••","phone":"+2"}""")
            .assertError(HttpStatusCode.NotFound, MSG_NOT_FOUND)
    }

    @Test
    fun deleteAccountRemovesFromJobsAndForgetsExport() = withServer { client ->
        app.store.updateConfig {
            it.copy(
                accounts = listOf(dummy("a1"), dummy("a2")),
                jobs = listOf(
                    Job(id = "j1", source = "@s", target = "@t", accountIds = listOf("a1", "a2")),
                    Job(id = "j2", source = "@s", target = "@t", accountIds = listOf("a2")),
                ),
            )
        }
        val session = dataDir.resolve("export/a1/session.properties")
        Files.createDirectories(session.parent)
        Files.writeString(session, "x=1")
        val tdlib = Files.createDirectories(dataDir.resolve("tdlib/a1"))
        client.postJson("/api/jobs/j1/start").assertOk()

        client.del("/api/accounts/a1").assertOk()

        assertFalse(app.jobs.isRunning("j1"))
        val cfg = app.store.config.value
        assertEquals(listOf("a2"), cfg.accounts.map { it.id })
        assertEquals(listOf(listOf("a2"), listOf("a2")), cfg.jobs.map { it.accountIds })
        assertFalse("j1" in cfg.autoResume)
        assertFalse(Files.exists(session.parent), "export session dir still on disk")
        assertFalse(Files.exists(tdlib), "TDLib dir still on disk")
        client.del("/api/accounts/a1").assertError(HttpStatusCode.NotFound, MSG_NOT_FOUND)
    }

    @Test
    fun deleteAndLogoutForgetTheLoadedChats() = withServer { client ->
        app.store.updateConfig { it.copy(accounts = listOf(dummy("a1"), dummy("a2"))) }
        app.accounts.sync(app.store.config.value.accounts)
        val chat = org.tgbackup.export.ChatEntry(
            peerId = 7, title = "ערוץ", username = "", type = org.tgbackup.export.data.DialogInfo.Type.PublicChannel,
            isForum = false, archived = false, input = org.tgbackup.mtproto.tl.MTPDinputPeerEmpty,
        )
        handles.putChats("a1", listOf(chat))
        handles.putChats("a2", listOf(chat))
        client.del("/api/accounts/a1").assertOk()
        kotlin.test.assertNull(handles.chat("a1", 7))
        kotlin.test.assertNotNull(handles.chat("a2", 7))
        // Logout forgets them too, whatever TDLib answers.
        client.postJson("/api/accounts/a2/logout")
        kotlin.test.assertNull(handles.chat("a2", 7))
    }

    @Test
    fun loginActionsWithoutClient() = withServer { client ->
        // apiId 0: a valid dialog save, but no TDLib client exists for it.
        app.store.updateConfig { it.copy(accounts = listOf(Account(id = "a1", apiId = 0, apiHash = hash, phone = "+1"))) }
        val none = "חסרים API ID / Hash"
        client.postJson("/api/accounts/a1/phone").assertError(HttpStatusCode.BadRequest, none)
        client.postJson("/api/accounts/a1/code", """{"code":"12345"}""").assertError(HttpStatusCode.BadRequest, none)
        client.postJson("/api/accounts/a1/password", """{"password":"pw"}""").assertError(HttpStatusCode.BadRequest, none)
        client.postJson("/api/accounts/a1/logout").assertError(HttpStatusCode.BadRequest, none)
        client.postJson("/api/accounts/zz/phone").assertError(HttpStatusCode.NotFound, MSG_NOT_FOUND)
    }

    @Test
    fun loginCodeIsNeverLogged() = withServer { client ->
        app.store.updateConfig { it.copy(accounts = listOf(dummy("a1"))) }
        app.accounts.sync(app.store.config.value.accounts)
        client.postJson("/api/accounts/a1/code", """{"code":"  "}""").assertError(HttpStatusCode.BadRequest, MSG_BAD_REQUEST)
        client.postJson("/api/accounts/a1/password", """{}""").assertError(HttpStatusCode.BadRequest, MSG_BAD_REQUEST)
        // The dummy client is not waiting for a code: TDLib answers with an error, shown as "שגיאה: …".
        val r = client.postJson("/api/accounts/a1/code", """{"code":"98765"}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("שגיאה: "), r.bodyAsText())
        val r2 = client.postJson("/api/accounts/a1/password", """{"password":"secret-pw-4321"}""")
        assertEquals(HttpStatusCode.BadRequest, r2.status)
        val log = app.log.entries.value.joinToString("\n") { it.msg }
        assertFalse(log.contains("98765"), log)
        assertFalse(log.contains("secret-pw-4321"), log)
    }
}

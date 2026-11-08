package org.tgbackup.desktop.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tgbackup.desktop.DesktopPrefs
import org.tgbackup.desktop.DesktopPrefsData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPrefsRoutesTest : RouteTestBase() {
    @AfterTest
    fun tearDown() = closeApp()

    private suspend fun io.ktor.client.HttpClient.prefs(path: String): Pair<Boolean, Boolean> {
        val res = get(path)
        val text = res.bodyAsText()
        assertEquals(HttpStatusCode.OK, res.status, text)
        val o = TgbJson.parseToJsonElement(text).jsonObject.let { if (path == "/api/state") it["desktopPrefs"]!!.jsonObject else it }
        return o["autostart"]!!.jsonPrimitive.boolean to o["keepAwake"]!!.jsonPrimitive.boolean
    }

    @Test
    fun getGivesTheDefaults() = withServer { client ->
        assertEquals(false to true, client.prefs("/api/desktop-prefs"))
        assertEquals(false to true, client.prefs("/api/state"))
    }

    @Test
    fun putSavesAndTheSnapshotCarriesIt() = withServer { client ->
        client.putJson("/api/desktop-prefs", """{"autostart":true,"keepAwake":false}""").assertOk()
        assertEquals(DesktopPrefsData(autostart = true, keepAwake = false), app.prefs.value.value)
        assertEquals(DesktopPrefsData(autostart = true, keepAwake = false), DesktopPrefs(dataDir.toFile()).value.value)
        assertEquals(true to false, client.prefs("/api/desktop-prefs"))
        assertEquals(true to false, client.prefs("/api/state"))
        client.putJson("/api/desktop-prefs", """{"autostart":false,"keepAwake":true}""").assertOk()
        assertEquals(false to true, client.prefs("/api/desktop-prefs"))
    }

    @Test
    fun aBadBodyIs400AndChangesNothing() = withServer { client ->
        val res = client.putJson("/api/desktop-prefs", """{"autostart":"yes"}""")
        assertEquals(HttpStatusCode.BadRequest, res.status, res.bodyAsText())
        assertEquals(DesktopPrefsData(), app.prefs.value.value)
    }
}

package org.tgbackup.desktop.server

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tgbackup.app.data.Account
import org.tgbackup.app.data.Job
import org.tgbackup.app.data.JobMode
import org.tgbackup.app.data.MediaType
import org.tgbackup.app.engine.JobStatus
import org.tgbackup.app.tg.AuthUi
import org.tgbackup.export.ExportState
import org.tgbackup.export.ProcessingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DtoTest {
    @Test
    fun authVariantsMapToTypeAndLabel() {
        assertEquals(AuthDto("Starting", "מאתחל…"), AuthUi.Starting.toDto())
        assertEquals(AuthDto("NeedPhone", "מנותק"), AuthUi.NeedPhone.toDto())
        assertEquals(AuthDto("NeedCode", "ממתין לקוד"), AuthUi.NeedCode.toDto())
        assertEquals(
            AuthDto("NeedPassword", "ממתין לסיסמת אימות דו-שלבי", hint = "רמז"),
            AuthUi.NeedPassword("רמז").toDto(),
        )
        assertEquals(AuthDto("Ready", "מחובר", display = "Dana"), AuthUi.Ready("Dana").toDto())
        assertEquals(AuthDto("Unsupported", "סיבה", why = "סיבה"), AuthUi.Unsupported("סיבה").toDto())
        assertEquals(AuthDto("Closed", "נסגר"), AuthUi.Closed.toDto())
    }

    @Test
    fun jobStatusLinksEqualEngineGetters() {
        val s = JobStatus(
            running = true, phase = "x", lastId = 42, sourceChatId = -1001234567890,
            copied = 3, failed = 1, targetChatId = -1009876543210, lastTargetId = 7,
        )
        val dto = s.toDto()
        assertEquals(s.lastLink, dto.lastLink)
        assertEquals("https://t.me/c/1234567890/42", dto.lastLink)
        assertEquals(s.lastTargetLink, dto.lastTargetLink)
        assertEquals(3, dto.copied)
        assertNull(JobStatus().toDto().lastLink)
        assertNull(JobStatus().toDto().latestLink)
        val latest = JobStatus(sourceChatId = -1001234567890, latestId = 50_000).toDto()
        assertEquals("https://t.me/c/1234567890/50000", latest.latestLink)
    }

    @Test
    fun jobStatusCarriesTheRunTotals() {
        val dto = JobStatus(copiedTotal = 12_340, runMillis = 8_045_000, runningSince = 1_700_000_000_000, latestId = 50_000).toDto()
        assertEquals(12_340L, dto.copiedTotal)
        assertEquals(8_045_000L, dto.runMillis)
        assertEquals(1_700_000_000_000L, dto.runningSince)
        assertEquals(50_000L, dto.latestId)
        val json = TgbJson.parseToJsonElement(TgbJson.encodeToString(JobStatusDto.serializer(), dto)).jsonObject
        for (key in listOf("copiedTotal", "runMillis", "runningSince", "latestId")) assertTrue(key in json, "$key in $json")
    }

    @Test
    fun accountDtoNeverContainsTheApiHash() {
        val hash = "abcdef0123456789abcdef0123456789"
        val dto = Account(id = "a1", name = "n", apiId = 5, apiHash = hash, phone = "+1").toDto()
        assertTrue(dto.hasApiHash)
        val json = TgbJson.encodeToString(AccountDto.serializer(), dto)
        assertFalse(json.contains(hash), json)
        assertFalse(json.contains("apiHash"), json)
        assertFalse(Account(apiHash = " ").toDto().hasApiHash)
    }

    @Test
    fun jobDtoCarriesEveryJobField() {
        val job = Job(
            id = "j", name = "n", source = "s", target = "t", mediaType = MediaType.PHOTO, mode = JobMode.FAST,
            accountIds = listOf("a"), startFromId = 9, stripLinks = false, skipArabic = true,
            perMinuteLimit = 10, viaTakeout = true,
        )
        val o = TgbJson.encodeToJsonElement(JobDto.serializer(), job.toDto()).jsonObject
        assertEquals("PHOTO", o["mediaType"]!!.jsonPrimitive.content)
        assertEquals("FAST", o["mode"]!!.jsonPrimitive.content)
        assertEquals(12, o.size, o.toString())
    }

    @Test
    fun apiErrorExportState() {
        val o = TgbJson.encodeToJsonElement(ExportStateDto.serializer(), ExportState.ApiError(400, "TAKEOUT_INVALID").toDto())
            .jsonObject
        assertEquals("ApiError", o["type"]!!.jsonPrimitive.content)
        assertEquals(400, o["code"]!!.jsonPrimitive.int)
        assertEquals("TAKEOUT_INVALID", o["errorType"]!!.jsonPrimitive.content)
    }

    @Test
    fun processingSnapshotsEveryField() {
        val p = ProcessingState().apply {
            step = ProcessingState.Step.Dialogs
            entityType = ProcessingState.EntityType.Chat
            entityName = "צ'אט"
            itemIndex = 4
            bytesLoaded = 100
            bytesCount = 200
        }
        val dto = ExportState.Processing(p).toDto() as ExportStateDto.Processing
        assertEquals("Dialogs", dto.state.step)
        assertEquals("Chat", dto.state.entityType)
        assertEquals(4, dto.state.itemIndex)
        p.itemIndex = 5
        assertEquals(4, dto.state.itemIndex, "a snapshot, not a live view")
        val o = TgbJson.encodeToJsonElement(ExportStateDto.serializer(), dto).jsonObject
        assertEquals("Processing", o["type"]!!.jsonPrimitive.content)
        assertEquals(14, o["state"]!!.jsonObject.size)
        val f = TgbJson.encodeToJsonElement(ExportStateDto.serializer(), ExportState.Finished("/p", 2, 3).toDto()).jsonObject
        assertEquals("Finished", f["type"]!!.jsonPrimitive.content)
        assertEquals(2, f["filesCount"]!!.jsonPrimitive.int)
        val idle = TgbJson.encodeToJsonElement(ExportStateDto.serializer(), ExportState.Idle.toDto()).jsonObject
        assertEquals("Idle", idle["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun aPasswordRequestCarriesWrong() {
        val again = org.tgbackup.app.export.PasswordRequest(
            "רמז", kotlinx.coroutines.CompletableDeferred(), "a1", org.tgbackup.app.export.PasswordOrigin.Job, wrong = true,
        ).toDto("p1")
        assertEquals(PasswordRequestDto("p1", "רמז", "a1", "job", wrong = true), again)
        val first = org.tgbackup.app.export.PasswordRequest("", kotlinx.coroutines.CompletableDeferred()).toDto("p2")
        assertFalse(first.wrong)
        assertEquals("screen", first.origin)
        val json = TgbJson.parseToJsonElement(TgbJson.encodeToString(PasswordRequestDto.serializer(), again)).jsonObject
        assertEquals("true", json["wrong"]!!.jsonPrimitive.content)
    }
}

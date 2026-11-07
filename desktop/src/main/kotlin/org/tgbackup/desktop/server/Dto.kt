package org.tgbackup.desktop.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.tgbackup.app.data.Account
import org.tgbackup.app.data.Config
import org.tgbackup.app.data.Job
import org.tgbackup.app.data.JobMode
import org.tgbackup.app.data.MediaType
import org.tgbackup.app.engine.JobStatus
import org.tgbackup.app.engine.LogEntry
import org.tgbackup.app.export.ExportPrefs
import org.tgbackup.app.export.RunningExport
import org.tgbackup.app.tg.AccountClient
import org.tgbackup.app.tg.AuthUi
import org.tgbackup.desktop.DesktopApp
import org.tgbackup.export.ExportState
import org.tgbackup.export.ProcessingState

/** The engine state as JSON. Nothing here carries a secret: accounts expose [AccountDto.hasApiHash] only. */

@Serializable
data class AccountDto(val id: String, val name: String, val apiId: Int, val hasApiHash: Boolean, val phone: String)

fun Account.toDto() = AccountDto(id, name, apiId, apiHash.isNotBlank(), phone)

@Serializable
data class JobDto(
    val id: String,
    val name: String,
    val source: String,
    val target: String,
    val mediaType: MediaType,
    val mode: JobMode,
    val accountIds: List<String>,
    val startFromId: Long,
    val stripLinks: Boolean,
    val skipArabic: Boolean,
    val perMinuteLimit: Int,
    val viaTakeout: Boolean,
)

fun Job.toDto() = JobDto(
    id, name, source, target, mediaType, mode, accountIds, startFromId, stripLinks, skipArabic, perMinuteLimit, viaTakeout,
)

@Serializable
data class JobStatusDto(
    val running: Boolean,
    val paused: Boolean,
    val phase: String,
    val lastId: Long,
    val sourceChatId: Long,
    val sourceTitle: String,
    val isProtected: Boolean,
    val copied: Int,
    val failed: Int,
    val targetChatId: Long,
    val lastTargetId: Long,
    val lastLink: String?,
    val lastTargetLink: String?,
    /** Copied over all runs; [copied] is this run only. */
    val copiedTotal: Long,
    /** Saved run time; while [runningSince] > 0 the time since then comes on top. */
    val runMillis: Long,
    /** Epoch ms, 0 when stopped or paused. */
    val runningSince: Long,
    /** The source's newest message id, 0 = not known yet. */
    val latestId: Long,
)

fun JobStatus.toDto() = JobStatusDto(
    running, paused, phase, lastId, sourceChatId, sourceTitle, isProtected, copied, failed, targetChatId, lastTargetId,
    lastLink, lastTargetLink, copiedTotal, runMillis, runningSince, latestId,
)

@Serializable
data class AuthDto(
    val type: String,
    val label: String,
    val hint: String? = null,
    val display: String? = null,
    val why: String? = null,
)

fun AuthUi.toDto(): AuthDto = when (this) {
    AuthUi.Starting -> AuthDto("Starting", label)
    AuthUi.NeedPhone -> AuthDto("NeedPhone", label)
    AuthUi.NeedCode -> AuthDto("NeedCode", label)
    is AuthUi.NeedPassword -> AuthDto("NeedPassword", label, hint = hint)
    is AuthUi.Ready -> AuthDto("Ready", label, display = display)
    is AuthUi.Unsupported -> AuthDto("Unsupported", label, why = why)
    AuthUi.Closed -> AuthDto("Closed", label)
}

/** What the Android accounts screen shows for an account that has no TDLib client (no API ID or hash). */
val NoClientAuth: AuthUi = AuthUi.Unsupported("חסרים API ID / Hash")

@Serializable
data class AccountStateDto(val id: String, val auth: AuthDto, val lastError: String? = null)

fun accountState(id: String, client: AccountClient?): AccountStateDto =
    if (client == null) AccountStateDto(id, NoClientAuth.toDto()) else accountState(id, client.auth.value, client.lastError.value)

fun accountState(id: String, auth: AuthUi, lastError: String?) = AccountStateDto(id, auth.toDto(), lastError)

@Serializable
data class ConfigDto(val accounts: List<AccountDto>, val jobs: List<JobDto>)

fun Config.toDto() = ConfigDto(accounts.map { it.toDto() }, jobs.map { it.toDto() })

@Serializable
data class ExportPrefsDto(
    val mediaTypes: Int,
    val sizeLimit: Long,
    val format: String,
    val path: String,
    val availableAt: Map<String, Int>,
    val defaultPath: String,
)

fun ExportPrefs.toDto(defaultPath: String) = ExportPrefsDto(mediaTypes, sizeLimit, format, path, availableAt, defaultPath)

/** A field-by-field copy of the mutable [ProcessingState]; the enums travel as their names. */
@Serializable
data class ProcessingDto(
    val step: String,
    val substepsPassed: Int,
    val substepsNow: Int,
    val substepsTotal: Int,
    val entityType: String,
    val entityName: String,
    val entityIndex: Int,
    val entityCount: Int,
    val itemIndex: Int,
    val itemCount: Int,
    val bytesRandomId: Long,
    val bytesName: String,
    val bytesLoaded: Long,
    val bytesCount: Long,
)

fun ProcessingState.toDto() = ProcessingDto(
    step.name, substepsPassed, substepsNow, substepsTotal, entityType.name, entityName, entityIndex, entityCount,
    itemIndex, itemCount, bytesRandomId, bytesName, bytesLoaded, bytesCount,
)

/** One shape per [ExportState] variant, told apart by `type` (the class discriminator of [TgbJson]). */
@Serializable
sealed interface ExportStateDto {
    @Serializable @SerialName("Idle")
    data object Idle : ExportStateDto

    @Serializable @SerialName("Processing")
    data class Processing(val state: ProcessingDto) : ExportStateDto

    @Serializable @SerialName("ApiError")
    data class ApiError(val code: Int, val errorType: String) : ExportStateDto

    @Serializable @SerialName("OutputError")
    data class OutputError(val path: String) : ExportStateDto

    @Serializable @SerialName("Cancelled")
    data object Cancelled : ExportStateDto

    @Serializable @SerialName("Finished")
    data class Finished(val path: String, val filesCount: Int, val bytesCount: Long) : ExportStateDto
}

fun ExportState.toDto(): ExportStateDto = when (this) {
    is ExportState.Processing -> ExportStateDto.Processing(state.toDto())
    is ExportState.ApiError -> ExportStateDto.ApiError(code, type)
    is ExportState.OutputError -> ExportStateDto.OutputError(path)
    is ExportState.Finished -> ExportStateDto.Finished(path, filesCount, bytesCount)
    ExportState.Cancelled -> ExportStateDto.Cancelled
    ExportState.Idle -> ExportStateDto.Idle
}

@Serializable
data class RunningExportDto(val accountId: String, val title: String, val state: ExportStateDto)

fun RunningExport.toDto(state: ExportState = controller.state.value) = RunningExportDto(accountId, title, state.toDto())

@Serializable
data class PasswordRequestDto(val id: String, val hint: String)

@Serializable
data class LogEntryDto(val seq: Long, val time: String, val level: String, val msg: String)

fun LogEntry.toDto() = LogEntryDto(seq, time, level.name, msg)

@Serializable
data class LogEventDto(val entries: List<LogEntryDto>, val cleared: Boolean)

@Serializable
data class SnapshotDto(
    val version: String,
    val tdlibVersion: String,
    val accounts: List<AccountDto>,
    val accountStates: List<AccountStateDto>,
    val jobs: List<JobDto>,
    val statuses: Map<String, JobStatusDto>,
    val exportPrefs: ExportPrefsDto,
    val runningExport: RunningExportDto? = null,
    val password: PasswordRequestDto? = null,
    val log: List<LogEntryDto>,
    /** Accounts whose chats are loading, with the count so far (a page that reconnects mid-load needs it). */
    val chatsLoading: Map<String, Int> = emptyMap(),
)

/** One loaded chat. `type` is the `DialogInfo.Type` name; the MTP input peer stays on the server ([ChatHandles]). */
@Serializable
data class ChatDto(
    val peerId: Long,
    val title: String,
    val username: String,
    val type: String,
    val isForum: Boolean,
    val archived: Boolean,
)

fun org.tgbackup.export.ChatEntry.toDto() = ChatDto(peerId, title, username, type.name, isForum, archived)

@Serializable
data class TopicDto(val id: Int, val title: String)

fun org.tgbackup.export.TopicEntry.toDto() = TopicDto(id, title)

/** The `chatsProgress` event: [count] chats so far; [done] is true once the load ended (the entry is gone). */
@Serializable
data class ChatsProgressDto(val accountId: String, val count: Int, val done: Boolean = false)

/** TDLib version of the bundled tdl-coroutines build; read from a constant, no TDLib client needed. */
val TdlibVersion: String = dev.g000sha256.tdl.TdlClient.TDL_VERSION

fun DesktopApp.accountStates(accounts: List<Account>): List<AccountStateDto> {
    val clients = this.accounts.clients.value
    return accounts.map { accountState(it.id, clients[it.id]) }
}

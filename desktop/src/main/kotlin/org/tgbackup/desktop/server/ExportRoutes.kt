package org.tgbackup.desktop.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.tgbackup.app.data.Account
import org.tgbackup.desktop.DesktopApp
import org.tgbackup.export.ExportState
import java.awt.Desktop
import java.io.File
import kotlin.time.Duration.Companion.seconds

internal const val MSG_BAD_VALUE = "ערך לא תקין"
internal const val MSG_RELOAD_CHATS = "יש לטעון את הצ'אטים מחדש"
internal const val MSG_EXPORT_RUNNING = "ייצוא כבר פועל"
internal const val MSG_CANNOT_OPEN = "לא ניתן לפתוח במחשב הזה"
internal const val MSG_NOT_FINISHED = "אין ייצוא שהסתיים"
internal const val MSG_REQUEST_HANDLED = "הבקשה כבר טופלה"

/** The export formats `Format.valueOf` accepts, as the Format radios store them. */
private val ExportFormats = setOf("Html", "Json", "HtmlAndJson")
private const val MIB = 1024L * 1024

/** SizeLimitByIndex's range: 1 MB .. 4000 MB (kMaxFileSize). */
private val SizeLimits = MIB..4000 * MIB

/** The seven media checkboxes (0x01..0x40). */
private const val ALL_MEDIA_TYPES = 0x7F

/** How long `start` waits for ExportManager to publish the new export (it does so from its own coroutine). */
private val START_PUBLISH_TIMEOUT = 10.seconds

@Serializable
data class SessionDto(val loggedIn: Boolean)

@Serializable
data class ReadyDto(val ready: Boolean)

@Serializable
data class PeerInput(val peerId: Long)

/** A partial update of the export options: only the fields that are present change. */
@Serializable
data class ExportPrefsInput(
    val mediaTypes: Int? = null,
    val sizeLimit: Long? = null,
    val format: String? = null,
    val path: String? = null,
)

/** The Export button: `from`/`till` are unixtimes, 0 = unset (the oldest message / present). */
@Serializable
data class ExportStartInput(
    val accountId: String = "",
    val peerId: Long,
    val topicId: Int? = null,
    val from: Int = 0,
    val till: Int = 0,
)

/** The 2FA answer: [id] of the request it answers; a null [password] cancels (the dialog's `ביטול`). */
@Serializable
data class ExportPasswordInput(val id: String = "", val password: String? = null)

/**
 * Opens a file or folder with the PC's default app, `Show my data` on the desktop. [supported] and [open] are
 * the AWT calls; tests replace them (Windows CI has a desktop, Linux CI does not).
 */
class DesktopOpener(
    private val supported: () -> Boolean = {
        Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)
    },
    private val open: (File) -> Unit = { Desktop.getDesktop().open(it) },
) {
    /** Opens [path] on [Dispatchers.IO] (`Desktop.open` can block); 400 [MSG_CANNOT_OPEN] when it cannot. */
    suspend fun open(path: String) {
        val ok = withContext(Dispatchers.IO) {
            runCatching { supported() && run { open(File(path)); true } }.getOrDefault(false)
        }
        if (!ok) throw Refusal(HttpStatusCode.BadRequest, MSG_CANNOT_OPEN)
    }
}

/** Answers [block]'s value as JSON, or the [Refusal] it threw. */
private suspend inline fun <reified T : Any> RoutingContext.answer(crossinline block: suspend () -> T) {
    val value = try {
        block()
    } catch (r: Refusal) {
        call.respondError(r.status, r.message)
        return
    }
    call.respond(value)
}

/** `ExportManager` failures carry Telegram/MTProto (or engine) texts; Android shows them after its prefix. */
private fun Throwable.failure(prefix: String) = Refusal(HttpStatusCode.BadRequest, "$prefix: $message")

/**
 * Export screen commands (docs/ui-inventory.md §2b export session, §2c export screen). Every `{accountId}` must be
 * in the current config. Login, check, chats and topics of one account run one at a time ([ChatHandles.exclusive]).
 * Neither request bodies nor the 2FA password are ever logged.
 */
fun Route.exportRoutes(app: DesktopApp, events: Events, handles: ChatHandles, opener: DesktopOpener = DesktopOpener()) {
    val exports = app.exports
    val prefsLock = Mutex()
    val startLock = Mutex()

    fun find(id: String): Account =
        app.store.config.value.accounts.firstOrNull { it.id == id } ?: throw Refusal(HttpStatusCode.NotFound, MSG_NOT_FOUND)

    /**
     * Runs [block] alone for the path's account, with the account re-read once the turn comes, and the account's
     * [ChatHandles.generation] then (a delete or a logout during the load drops what it loaded).
     */
    suspend fun <T> RoutingContext.forAccount(block: suspend (Account, Long) -> T): T {
        val id = idParam("accountId")
        find(id)
        return handles.exclusive(id) {
            val since = handles.generation(id)
            block(find(id), since)
        }
    }

    route("/api/export") {
        // Read-only, so GET is fine (Origin is checked only on state-changing methods).
        get("{accountId}/session") {
            answer {
                val acc = find(idParam("accountId"))
                // As in Android: a failure reads as "not logged in".
                val loggedIn = try {
                    exports.isLoggedIn(acc)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                SessionDto(loggedIn)
            }
        }

        post("{accountId}/login") {
            command {
                forAccount { acc, _ ->
                    exports.login(acc).onFailure { if (it is CancellationException) throw it; throw it.failure("ההתחברות נכשלה") }
                }
            }
        }

        post("{accountId}/check") {
            answer {
                forAccount { acc, _ ->
                    val ready = exports.checkTakeoutReady(acc)
                        .getOrElse { if (it is CancellationException) throw it; throw it.failure("בדיקה נכשלה") }
                    ReadyDto(ready)
                }
            }
        }

        post("{accountId}/chats") {
            answer {
                forAccount { acc, since ->
                    events.chatsProgress(acc.id, 0)
                    val chats = try {
                        exports.loadChats(acc) { n -> events.chatsProgress(acc.id, n) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw e.failure("טעינת הצ'אטים נכשלה")
                    } finally {
                        events.chatsDone(acc.id)
                    }
                    handles.putChats(acc.id, chats, since)
                    chats.map { it.toDto() }
                }
            }
        }

        post("{accountId}/topics") {
            answer {
                val peerId = call.receive<PeerInput>().peerId
                forAccount { acc, since ->
                    val chat = handles.chat(acc.id, peerId) ?: throw Refusal(HttpStatusCode.BadRequest, MSG_RELOAD_CHATS)
                    val topics = try {
                        exports.loadTopics(acc, chat)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw e.failure("טעינת הנושאים נכשלה")
                    }
                    handles.putTopics(acc.id, peerId, topics, since)
                    topics.map { it.toDto() }
                }
            }
        }

        patch("prefs") {
            command {
                val input = call.receive<ExportPrefsInput>()
                // Validate every field first, so a bad one leaves the prefs untouched.
                if (input.format != null && input.format !in ExportFormats) throw Refusal(HttpStatusCode.BadRequest, MSG_BAD_VALUE)
                if (input.sizeLimit != null && input.sizeLimit !in SizeLimits) throw Refusal(HttpStatusCode.BadRequest, MSG_BAD_VALUE)
                if (input.mediaTypes != null && (input.mediaTypes and ALL_MEDIA_TYPES.inv()) != 0) {
                    throw Refusal(HttpStatusCode.BadRequest, MSG_BAD_VALUE)
                }
                prefsLock.withLock {
                    exports.updatePrefs { p ->
                        p.copy(
                            mediaTypes = input.mediaTypes ?: p.mediaTypes,
                            sizeLimit = input.sizeLimit ?: p.sizeLimit,
                            format = input.format ?: p.format,
                            path = input.path ?: p.path,
                        )
                    }
                }
            }
        }

        post("start") {
            command {
                val input = call.receive<ExportStartInput>()
                val acc = find(checkedId(input.accountId))
                if (input.from < 0 || input.till < 0) throw Refusal(HttpStatusCode.BadRequest, MSG_BAD_VALUE)
                startLock.withLock {
                    val current = exports.running.value
                    // A published export whose job has not completed (a lazy job not yet started counts too).
                    if (current != null && !current.job.isCompleted) throw Refusal(HttpStatusCode.Conflict, MSG_EXPORT_RUNNING)
                    val chat = handles.chat(acc.id, input.peerId) ?: throw Refusal(HttpStatusCode.BadRequest, MSG_RELOAD_CHATS)
                    val topic = input.topicId?.let {
                        handles.topic(acc.id, input.peerId, it) ?: throw Refusal(HttpStatusCode.BadRequest, MSG_RELOAD_CHATS)
                    }
                    // A finished export still on screen: `start` is a no-op until it is cleared.
                    if (current != null) exports.dismiss()
                    // SleepGuard follows the export job (DesktopApp), so nothing to do when it starts.
                    exports.start(acc, chat, topic, input.from, input.till) {}
                    // ExportManager publishes the export from its own coroutine: wait, so the next start sees it.
                    withTimeoutOrNull(START_PUBLISH_TIMEOUT) { exports.running.first { it != null } }
                }
            }
        }

        post("skip") { command { exports.skipFile() } }
        post("stop") { command { exports.stop() } }
        post("dismiss") { command { exports.dismiss() } }

        post("open") {
            command {
                val finished = exports.running.value?.controller?.state?.value as? ExportState.Finished
                    ?: throw Refusal(HttpStatusCode.Conflict, MSG_NOT_FINISHED)
                opener.open(finished.path)
            }
        }

        post("password") {
            command {
                val input = call.receive<ExportPasswordInput>()
                val request = exports.password.value
                // Only the request the page was shown may be answered; an old or unknown id is refused.
                if (request == null || events.passwordId(request) != input.id || !request.answer.complete(input.password)) {
                    throw Refusal(HttpStatusCode.Conflict, MSG_REQUEST_HANDLED)
                }
            }
        }
    }
}

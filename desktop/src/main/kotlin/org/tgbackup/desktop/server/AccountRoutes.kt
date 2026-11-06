package org.tgbackup.desktop.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.tgbackup.app.data.Account
import org.tgbackup.app.data.Config
import org.tgbackup.app.tg.AccountClient
import org.tgbackup.desktop.DesktopApp
import kotlin.time.Duration.Companion.seconds

internal const val MSG_BAD_ACCOUNT = "פרטי החשבון לא תקינים"
internal const val MSG_NO_CLIENT = "חסרים API ID / Hash"

/** What `GET` responses send in place of an API hash; on PUT it means "keep the stored hash". */
const val MASKED_HASH = "••••"

/** How long a delete waits for the TDLib logout and the export-session wipe before it answers. */
internal val PURGE_TIMEOUT = 20.seconds

/** How long a login action waits for TDLib before it answers 504 [MSG_TG_TIMEOUT]. */
internal val LOGIN_TIMEOUT = 60.seconds
internal const val MSG_TG_TIMEOUT = "טלגרם לא ענה בזמן, נסה שוב"

/** The AccountDialog's fields. `apiId` may be a number or the text field's digits. `id` is ignored: the server makes ids. */
@Serializable
data class AccountInput(
    val id: String = "",
    val name: String = "",
    val apiId: JsonPrimitive = JsonNull,
    val apiHash: String = "",
    val phone: String = "",
)

@Serializable
data class CodeInput(val code: String = "")

@Serializable
data class PasswordInput(val password: String = "")

/**
 * The dialog's save rule: `apiId` is digits that fit an Int, `apiHash.length >= 16`, `phone` not blank. Texts
 * are trimmed like the dialog does. [storedHash] replaces [MASKED_HASH] (PUT only).
 */
internal fun AccountInput.toAccount(id: String, storedHash: String?): Account {
    val idText = if (apiId is JsonNull) "" else apiId.content.trim()
    val parsedId = if (idText.isNotEmpty() && idText.all { it in '0'..'9' }) idText.toIntOrNull() else null
    val hash = if (storedHash != null && apiHash == MASKED_HASH) storedHash else apiHash.trim()
    if (parsedId == null || hash.length < 16 || phone.isBlank()) throw Refusal(HttpStatusCode.BadRequest, MSG_BAD_ACCOUNT)
    return Account(id = id, name = name.trim(), apiId = parsedId, apiHash = hash, phone = phone.trim())
}

/** Accounts commands, the Android accounts screen's rules (docs/ui-inventory.md §2b). */
fun Route.accountRoutes(app: DesktopApp, locks: CommandLocks, handles: ChatHandles = ChatHandles()) {
    val store = app.store

    fun find(id: String): Account =
        store.config.value.accounts.firstOrNull { it.id == id } ?: throw Refusal(HttpStatusCode.NotFound, MSG_NOT_FOUND)

    fun clientOf(id: String): AccountClient {
        find(id)
        return app.accounts[id] ?: throw Refusal(HttpStatusCode.BadRequest, MSG_NO_CLIENT)
    }

    /**
     * Runs a login action, bounded by [LOGIN_TIMEOUT]. Its result is null on success, else TDLib's error text.
     * Neither the input nor the result is logged here.
     */
    suspend fun login(action: suspend () -> String?) {
        var error: String? = null
        withTimeoutOrNull(LOGIN_TIMEOUT) { error = action() }
            ?: throw Refusal(HttpStatusCode.GatewayTimeout, MSG_TG_TIMEOUT)
        error?.let { throw Refusal(HttpStatusCode.BadRequest, "שגיאה: $it") }
    }

    fun Config.withAccount(acc: Account): Config =
        if (accounts.any { it.id == acc.id }) copy(accounts = accounts.map { if (it.id == acc.id) acc else it })
        else copy(accounts = accounts + acc)

    route("/api/accounts") {
        post {
            val input = call.receive<AccountInput>()
            command {
                locks.config {
                    val acc = input.toAccount(newAccountId(store.config.value), storedHash = null)
                    store.updateConfig { it.withAccount(acc) }
                }
            }
        }

        put("{id}") {
            command {
                val id = idParam()
                val input = call.receive<AccountInput>()
                locks.config {
                    val acc = input.toAccount(id, storedHash = find(id).apiHash)
                    store.updateConfig { it.withAccount(acc) }
                }
            }
        }

        delete("{id}") {
            command { deleteAccount(app, locks, handles, idParam()) }
        }

        post("{id}/phone") {
            command {
                val client = clientOf(idParam())
                login { client.sendPhone() }
            }
        }
        post("{id}/code") {
            command {
                val client = clientOf(idParam())
                val code = call.receive<CodeInput>().code
                if (code.isBlank()) throw Refusal(HttpStatusCode.BadRequest, MSG_BAD_REQUEST)
                login { client.sendCode(code) }
            }
        }
        post("{id}/password") {
            command {
                val client = clientOf(idParam())
                val password = call.receive<PasswordInput>().password
                if (password.isBlank()) throw Refusal(HttpStatusCode.BadRequest, MSG_BAD_REQUEST)
                login { client.sendPassword(password) }
            }
        }
        post("{id}/logout") {
            command {
                val id = idParam()
                clientOf(id)
                // The export screen's chats were loaded with this login: they go with it.
                handles.forget(id)
                // In the app scope: a timeout must not cancel logout halfway (between the wipe and the fresh client).
                val logout = app.scope.async { app.accounts.logout(id) }
                login { logout.await(); null }
            }
        }
    }
}

/**
 * The Android delete order: stop the running jobs that use the account, remove it from the accounts and from
 * every `job.accountIds`, then `accounts.purge(id)` and `exports.forget(id)` (Android skipped the last one and left
 * the export session on disk). The jobs that hold the account are locked, so none of them starts in between. The
 * export screen's loaded chats of the account are dropped as soon as it is gone from the config.
 */
private suspend fun deleteAccount(app: DesktopApp, locks: CommandLocks, handles: ChatHandles, id: String) {
    val store = app.store
    while (true) {
        val holders = store.config.value.jobs.filter { id in it.accountIds }.map { it.id }.toSet()
        val done = locks.jobs(holders) {
            locks.config {
                val cfg = store.config.value
                if (cfg.jobs.filter { id in it.accountIds }.map { it.id }.toSet() != holders) return@config false
                if (cfg.accounts.none { it.id == id }) throw Refusal(HttpStatusCode.NotFound, MSG_NOT_FOUND)
                holders.filter { app.jobs.isRunning(it) }.forEach { app.jobs.stop(it) }
                store.updateConfig { c ->
                    c.copy(
                        accounts = c.accounts.filter { it.id != id },
                        jobs = c.jobs.map { j -> if (id in j.accountIds) j.copy(accountIds = j.accountIds - id) else j },
                    )
                }
                true
            }
        }
        if (done) {
            handles.forget(id)
            break
        }
        // A job gained or lost the account while we waited for the locks: take the new set.
    }

    // The wipe runs in the app scope, so a slow TDLib logout finishes even when this request stops waiting.
    val wipe = app.scope.async {
        runCatching { app.accounts.purge(id) }
            .onFailure { app.log.warn("[$id] מחיקת נתוני החשבון נכשלה: ${it.javaClass.simpleName}") }
        runCatching { app.exports.forget(id) }
            .onFailure { app.log.warn("[$id] מחיקת סשן הייצוא נכשלה: ${it.javaClass.simpleName}") }
    }
    if (withTimeoutOrNull(PURGE_TIMEOUT) { wipe.await() } == null) {
        app.log.warn("[$id] מחיקת נתוני החשבון לא הסתיימה תוך 20 שניות")
    }
}

/** A fresh 8-character id, the same shape as `Account().id`, not used by any account. */
private fun newAccountId(cfg: Config): String {
    while (true) {
        val id = Account().id
        if (cfg.accounts.none { it.id == id }) return id
    }
}

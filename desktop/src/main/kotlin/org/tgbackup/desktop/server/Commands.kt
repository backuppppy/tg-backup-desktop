package org.tgbackup.desktop.server

import io.ktor.http.ContentType
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** The reply of every command that succeeded. */
suspend fun ApplicationCall.respondOk() {
    respondText(
        buildJsonObject { put("ok", true) }.toString(),
        ContentType.Application.Json.withCharset(Charsets.UTF_8),
    )
}

/**
 * Serializes the commands, so two parallel requests never leave a half-saved state.
 *
 * - One lock per job id is held around "is it running?" and the action (start, edit, delete, reset), so two
 *   requests on the same job can never both pass the check.
 * - [config] is held around every read-check-write of the accounts and jobs lists (the "account already used by
 *   another job" rule reads every job, so per-job locks alone cannot cover it).
 *
 * Lock order is always job locks (sorted by id) first, then [config]. Locks are not reentrant.
 */
class CommandLocks {
    private val perJob = ConcurrentHashMap<String, Mutex>()
    private val config = Mutex()

    suspend fun <T> job(id: String, block: suspend () -> T): T = jobs(listOf(id), block)

    suspend fun <T> jobs(ids: Collection<String>, block: suspend () -> T): T {
        val locks = ids.toSortedSet().map { perJob.computeIfAbsent(it) { Mutex() } }
        return lockAll(locks, 0, block)
    }

    suspend fun <T> config(block: suspend () -> T): T = config.withLock { block() }

    private suspend fun <T> lockAll(locks: List<Mutex>, i: Int, block: suspend () -> T): T =
        if (i == locks.size) block() else locks[i].withLock { lockAll(locks, i + 1, block) }
}

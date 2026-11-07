package org.tgbackup.desktop.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.tgbackup.app.data.Config
import org.tgbackup.app.data.Job
import org.tgbackup.app.data.JobMode
import org.tgbackup.app.data.MediaType
import org.tgbackup.desktop.DesktopApp

internal const val MSG_JOB_RUNNING = "לא ניתן לשנות משימה בזמן שהיא פועלת"
internal const val MSG_NO_SOURCE_OR_TARGET = "חסר מקור או יעד"
internal const val MSG_NO_ACCOUNTS = "לא נבחרו חשבונות למשימה"

/** The JobDialog's fields. Missing fields take the dialog's defaults. `id` is always ignored: the server makes ids. */
@Serializable
data class JobInput(
    val id: String = "",
    val name: String = "",
    val source: String = "",
    val target: String = "",
    val mediaType: MediaType = MediaType.VIDEO_DOC,
    val mode: JobMode = JobMode.ORDERED,
    val accountIds: List<String> = emptyList(),
    val startFromId: Long = 0,
    val stripLinks: Boolean = true,
    val skipArabic: Boolean = false,
    val perMinuteLimit: Int = 25,
    val viaTakeout: Boolean = false,
)

/** A refused command: answered as `{"error": message}` with [status]. */
internal class Refusal(val status: HttpStatusCode, override val message: String) :
    RuntimeException(message, null, false, false)

/** Runs [block]; answers `{"ok":true}`, or the [Refusal] it threw. */
internal suspend fun RoutingContext.command(block: suspend () -> Unit) {
    try {
        block()
    } catch (r: Refusal) {
        call.respondError(r.status, r.message)
        return
    }
    call.respondOk()
}

/** The dialog's save: trimmed texts, the selected accounts in config order, `startFromId` 0 when not set. */
internal fun JobInput.toJob(id: String, cfg: Config): Job {
    val selected = accountIds.toSet()
    return Job(
        id = id,
        name = name.trim(),
        source = source.trim(),
        target = target.trim(),
        mediaType = mediaType,
        mode = mode,
        accountIds = cfg.accounts.filter { it.id in selected }.map { it.id },
        startFromId = startFromId.coerceAtLeast(0),
        stripLinks = stripLinks,
        skipArabic = skipArabic,
        // The dialog takes digits only and turns anything else into 25.
        perMinuteLimit = if (perMinuteLimit < 0) 25 else perMinuteLimit,
        viaTakeout = viaTakeout,
    )
}

/** The dialog's save-enable rule, plus "one account, one job": an account in any other job is taken. */
internal fun validateJob(job: Job, cfg: Config) {
    if (job.source.isBlank() || job.target.isBlank()) throw Refusal(HttpStatusCode.BadRequest, MSG_NO_SOURCE_OR_TARGET)
    if (job.accountIds.isEmpty()) throw Refusal(HttpStatusCode.BadRequest, MSG_NO_ACCOUNTS)
    val taken = cfg.jobs.filter { it.id != job.id }.flatMap { it.accountIds }.toSet()
    val clash = cfg.accounts.filter { it.id in job.accountIds && it.id in taken }
    if (clash.isNotEmpty()) {
        val names = clash.joinToString(", ") { it.name.ifBlank { it.phone } }
        throw Refusal(HttpStatusCode.BadRequest, "החשבון $names כבר עובד במשימה אחרת")
    }
}

/**
 * Jobs commands, the Android jobs screen's rules (docs/ui-inventory.md §2a). Every command is POST, PUT or
 * DELETE: Origin is checked only on those.
 */
fun Route.jobRoutes(app: DesktopApp, locks: CommandLocks) {
    val store = app.store
    val jobs = app.jobs

    fun exists(id: String) = store.config.value.jobs.any { it.id == id }
    fun requireJob(id: String) {
        if (!exists(id)) throw Refusal(HttpStatusCode.NotFound, MSG_NOT_FOUND)
    }
    fun requireStopped(id: String) {
        if (jobs.isRunning(id)) throw Refusal(HttpStatusCode.Conflict, MSG_JOB_RUNNING)
    }

    /**
     * Saves [input] as job [id]: replace the existing job, or append a new one when [create]. Then reads the
     * source's latest id in the background (nothing happens when no account of the job is logged in).
     */
    suspend fun save(id: String, input: JobInput, create: Boolean) {
        locks.job(id) {
            requireStopped(id)
            locks.config {
                val cfg = store.config.value
                if (!create) requireJob(id)
                val job = input.toJob(id, cfg)
                validateJob(job, cfg)
                val old = cfg.jobs.firstOrNull { it.id == id }
                store.updateConfig { c ->
                    if (c.jobs.any { it.id == id }) c.copy(jobs = c.jobs.map { if (it.id == id) job else it })
                    else c.copy(jobs = c.jobs + job)
                }
                // The old source's newest id says nothing about the new one.
                if (old != null && old.source != job.source && store.progress(id).latestId != 0L) {
                    store.saveProgress(id) { it.copy(latestId = 0) }
                    jobs.showSavedProgress(id)
                }
            }
        }
        app.scope.launch { jobs.refreshLatest(id) }
    }

    route("/api/jobs") {
        post {
            val input = call.receive<JobInput>()
            command { save(newJobId(store.config.value), input, create = true) }
        }

        // "all" is a fixed segment, so it wins over {id}.
        post("all/start") {
            // startAll()'s list, with each start under its job's lock.
            val errors = store.config.value.jobs.mapNotNull { j ->
                locks.job(j.id) {
                    if (jobs.isRunning(j.id)) null
                    else locks.start { jobs.start(j.id) }?.let { "${j.name.ifBlank { j.id }}: $it" }
                }
            }
            call.respondText(
                buildJsonObject { putJsonArray("errors") { errors.forEach { add(it) } } }.toString(),
                ContentType.Application.Json.withCharset(Charsets.UTF_8),
            )
        }
        post("all/pause") { command { jobs.pauseAll() } }
        post("all/resume") { command { jobs.resumeAll() } }
        // stopAll(), with each stop under its job's lock so it cannot interleave with a start or an edit.
        post("all/stop") {
            command {
                store.config.value.jobs.forEach { j -> locks.job(j.id) { if (jobs.isRunning(j.id)) jobs.stop(j.id) } }
            }
        }

        put("{id}") {
            command {
                val id = idParam()
                val input = call.receive<JobInput>()
                save(id, input, create = false)
            }
        }

        delete("{id}") {
            command {
                val id = idParam()
                locks.job(id) {
                    requireJob(id)
                    requireStopped(id)
                    locks.config {
                        requireJob(id)
                        // The config first: a refreshLatest that ends after forget sees no job and saves nothing.
                        store.updateConfig { c -> c.copy(jobs = c.jobs.filter { it.id != id }) }
                        jobs.forget(id)
                    }
                }
            }
        }

        post("{id}/start") {
            command {
                val id = idParam()
                locks.job(id) {
                    requireJob(id)
                    locks.start { jobs.start(id) }?.let { throw Refusal(HttpStatusCode.Conflict, it) }
                }
            }
        }
        post("{id}/stop") {
            command { val id = idParam(); locks.job(id) { requireJob(id); jobs.stop(id) } }
        }
        post("{id}/pause") {
            command { val id = idParam(); locks.job(id) { requireJob(id); jobs.pause(id) } }
        }
        post("{id}/resume") {
            command { val id = idParam(); locks.job(id) { requireJob(id); jobs.resume(id) } }
        }
        // Reads the source's newest message id without starting the job; answers it, 0 when it could not be read
        // (no logged-in account of the job). The value also reaches the job's status.
        post("{id}/latest") {
            val latest = try {
                val id = idParam()
                requireJob(id)
                jobs.refreshLatest(id) ?: 0L
            } catch (r: Refusal) {
                call.respondError(r.status, r.message)
                return@post
            }
            call.respondText(
                buildJsonObject { put("ok", true); put("latestId", latest) }.toString(),
                ContentType.Application.Json.withCharset(Charsets.UTF_8),
            )
        }
        post("{id}/reset") {
            command {
                val id = idParam()
                locks.job(id) {
                    requireJob(id)
                    requireStopped(id)
                    jobs.resetProgress(id)
                }
            }
        }
    }
}

/** A fresh 8-character id, the same shape as `Job().id`, not used by any job. */
private fun newJobId(cfg: Config): String {
    while (true) {
        val id = Job().id
        if (cfg.jobs.none { it.id == id }) return id
    }
}

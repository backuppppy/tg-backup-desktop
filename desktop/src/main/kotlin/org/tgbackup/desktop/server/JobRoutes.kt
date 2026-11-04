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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import org.tgbackup.app.data.Config
import org.tgbackup.app.data.Job
import org.tgbackup.app.data.JobMode
import org.tgbackup.app.data.MediaType
import org.tgbackup.desktop.DesktopApp

internal const val MSG_JOB_NOT_FOUND = "המשימה לא נמצאה"
internal const val MSG_JOB_RUNNING = "לא ניתן לשנות משימה בזמן שהיא פועלת"
internal const val MSG_NO_SOURCE_OR_TARGET = "חסר מקור או יעד"
internal const val MSG_NO_ACCOUNTS = "לא נבחרו חשבונות למשימה"

/** The JobDialog's fields. Missing fields take the dialog's defaults; `id` is ignored on PUT. */
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

internal fun RoutingContext.idParam(): String = checkNotNull(call.parameters["id"])

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
        if (!exists(id)) throw Refusal(HttpStatusCode.NotFound, MSG_JOB_NOT_FOUND)
    }
    fun requireStopped(id: String) {
        if (jobs.isRunning(id)) throw Refusal(HttpStatusCode.Conflict, MSG_JOB_RUNNING)
    }

    /** Saves [input] as job [id]: replace by id, or append when [create]. */
    suspend fun save(id: String, input: JobInput, create: Boolean) = locks.job(id) {
        requireStopped(id)
        locks.config {
            val cfg = store.config.value
            if (!create) requireJob(id)
            val job = input.toJob(id, cfg)
            validateJob(job, cfg)
            store.updateConfig { c ->
                if (c.jobs.any { it.id == id }) c.copy(jobs = c.jobs.map { if (it.id == id) job else it })
                else c.copy(jobs = c.jobs + job)
            }
        }
    }

    route("/api/jobs") {
        post {
            val input = call.receive<JobInput>()
            command {
                val id = input.id.trim().ifBlank { newJobId(store.config.value) }
                save(id, input, create = true)
            }
        }

        // "all" is a fixed segment, so it wins over {id}.
        post("all/start") {
            // startAll()'s list, with each start under its job's lock.
            val errors = store.config.value.jobs.mapNotNull { j ->
                locks.job(j.id) {
                    if (jobs.isRunning(j.id)) null else jobs.start(j.id)?.let { "${j.name.ifBlank { j.id }}: $it" }
                }
            }
            call.respondText(
                buildJsonObject { putJsonArray("errors") { errors.forEach { add(it) } } }.toString(),
                ContentType.Application.Json.withCharset(Charsets.UTF_8),
            )
        }
        post("all/pause") { command { jobs.pauseAll() } }
        post("all/resume") { command { jobs.resumeAll() } }
        post("all/stop") { command { jobs.stopAll() } }

        put("{id}") {
            val id = idParam()
            val input = call.receive<JobInput>()
            command { save(id, input, create = false) }
        }

        delete("{id}") {
            val id = idParam()
            command {
                locks.job(id) {
                    requireStopped(id)
                    locks.config {
                        requireJob(id)
                        jobs.forget(id)
                        store.updateConfig { c -> c.copy(jobs = c.jobs.filter { it.id != id }) }
                    }
                }
            }
        }

        post("{id}/start") {
            val id = idParam()
            command {
                locks.job(id) { jobs.start(id)?.let { throw Refusal(HttpStatusCode.Conflict, it) } }
            }
        }
        post("{id}/stop") {
            val id = idParam()
            command { locks.job(id) { requireJob(id); jobs.stop(id) } }
        }
        post("{id}/pause") {
            val id = idParam()
            command { requireJob(id); jobs.pause(id) }
        }
        post("{id}/resume") {
            val id = idParam()
            command { requireJob(id); jobs.resume(id) }
        }
        post("{id}/reset") {
            val id = idParam()
            command {
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

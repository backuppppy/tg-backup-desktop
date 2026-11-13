package org.tgbackup.desktop.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/** What `tailscale status --json` says about this PC. */
sealed interface TsStatus {
    data object NotInstalled : TsStatus
    data object NotConnected : TsStatus

    /** Connected as [dnsName] (without the trailing dot); [httpsOn] when the tailnet issues certificates. */
    data class Up(val dnsName: String, val httpsOn: Boolean) : TsStatus
    data class Failed(val detail: String) : TsStatus
}

/** Port 443 in `tailscale serve status --json`: our one entry, anything else, or nothing. */
enum class ServeConfig { Ours, Busy, None }

/** A failed CLI call: [detail] is Tailscale's own message (or `timeout`). */
class TsError(val detail: String) : Exception(detail)

/** The few `tailscale` calls the program makes; a fake in tests. */
interface TailscaleCli {
    fun status(): TsStatus

    /** Throws [TsError]. */
    fun serveStatus(): ServeConfig

    /** https 443 of the tailnet name → `http://127.0.0.1:<port>`, kept by Tailscale (`--bg`). Throws [TsError]. */
    fun serveOn(port: Int)

    /** Removes https 443 (called only when it is ours). Throws [TsError]. */
    fun serveOff()
}

/** The proxy target of our serve entry. */
private const val OUR_PROXY = "http://127.0.0.1:47800"
private const val HTTPS_PORT = "443"

private val json = Json { ignoreUnknownKeys = true }

/** `tailscale status --json`: `BackendState` other than `Running` → NotConnected; else Up, or Failed when unreadable. */
fun parseStatus(text: String): TsStatus {
    val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        ?: return TsStatus.Failed("unreadable status")
    val state = root.str("BackendState") ?: return TsStatus.Failed("unreadable status")
    if (state != "Running") return TsStatus.NotConnected
    val name = (root["Self"] as? JsonObject)?.str("DNSName")?.trim()?.removeSuffix(".")
    if (name.isNullOrEmpty()) return TsStatus.Failed("no DNS name")
    val certs = root["CertDomains"] as? JsonArray
    return TsStatus.Up(name, httpsOn = !certs.isNullOrEmpty())
}

/**
 * `tailscale serve status --json` for port 443. Ours only when 443 is exactly our entry: TCP 443 `{"HTTPS":true}`
 * and every `<name>:443` web entry is the one handler `/` → [OUR_PROXY]. Anything else there (another handler or path, a
 * TCP forward, Funnel, a foreground `serve` session) is Busy. Other ports are not ours to look at.
 */
fun parseServeStatus(text: String): ServeConfig {
    if (text.isBlank()) return ServeConfig.None
    val root = runCatching { json.parseToJsonElement(text) }.getOrElse { throw TsError("unreadable serve status") }
    if (root is JsonNull) return ServeConfig.None
    if (root !is JsonObject) throw TsError("unreadable serve status")

    val foregroundOn443 = (root["Foreground"] as? JsonObject)?.values.orEmpty()
        .any { s -> s is JsonObject && (s.tcp443() != null || s.web443().isNotEmpty()) }
    val funnelOn443 = (root["AllowFunnel"] as? JsonObject).orEmpty()
        .any { (k, v) -> k.endsWith(":$HTTPS_PORT") && (v as? JsonPrimitive)?.booleanOrNull == true }
    val tcp = root.tcp443()
    val web = root.web443()
    if (tcp == null && web.isEmpty() && !foregroundOn443 && !funnelOn443) return ServeConfig.None
    if (foregroundOn443 || funnelOn443) return ServeConfig.Busy

    val ourTcp = tcp is JsonObject && tcp.keys == setOf("HTTPS") && (tcp["HTTPS"] as? JsonPrimitive)?.booleanOrNull == true
    val ourWeb = web.isNotEmpty() && web.all { entry ->
        val handlers = (entry as? JsonObject)?.get("Handlers") as? JsonObject
        val slash = handlers?.get("/") as? JsonObject
        handlers?.keys == setOf("/") && slash?.keys == setOf("Proxy") && slash.str("Proxy") == OUR_PROXY
    }
    return if (ourTcp && ourWeb) ServeConfig.Ours else ServeConfig.Busy
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.tcp443(): JsonElement? = (this["TCP"] as? JsonObject)?.get(HTTPS_PORT)?.takeIf { it !is JsonNull }

private fun JsonObject.web443(): List<JsonElement> =
    (this["Web"] as? JsonObject).orEmpty().filterKeys { it.endsWith(":$HTTPS_PORT") }.values.toList()

private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

/** `tailscale` on PATH, else the Windows installer's place; null when neither is there. */
fun findTailscale(
    path: String? = System.getenv("PATH"),
    windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    installed: File = File("C:\\Program Files\\Tailscale\\tailscale.exe"),
): File? {
    val name = if (windows) "tailscale.exe" else "tailscale"
    path.orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
        .map { File(it.trim().removeSurrounding("\""), name) }
        .firstOrNull { it.isFile }?.let { return it }
    return installed.takeIf { windows && it.isFile }
}

/**
 * The real `tailscale` CLI at [exe] (null: not installed). Every call ends within [timeoutMs]: a call still running
 * then is killed (with its child processes) and fails with `timeout`. Blocking: callers run it on `Dispatchers.IO`.
 */
class ProcessTailscale(private val exe: File?, private val timeoutMs: Long = TIMEOUT_MS) : TailscaleCli {

    override fun status(): TsStatus {
        if (exe == null || !exe.isFile) return TsStatus.NotInstalled
        val r = try {
            run("status", "--json")
        } catch (e: TsError) {
            return TsStatus.Failed(e.detail)
        }
        // `status --json` answers with JSON also when stopped or logged out (exit 0 or not).
        if (r.out.trimStart().startsWith("{")) return parseStatus(r.out)
        // The service itself is not running: installed, not connected.
        if (r.out.contains("failed to connect to local", ignoreCase = true)) return TsStatus.NotConnected
        return TsStatus.Failed(r.detail())
    }

    override fun serveStatus(): ServeConfig {
        val r = run("serve", "status", "--json")
        if (r.code != 0) throw TsError(r.detail())
        return parseServeStatus(r.out)
    }

    override fun serveOn(port: Int) {
        val r = run("serve", "--bg", "--https=$HTTPS_PORT", "http://127.0.0.1:$port")
        if (r.code != 0) throw TsError(r.detail())
    }

    override fun serveOff() {
        val r = run("serve", "--https=$HTTPS_PORT", "off")
        if (r.code != 0) throw TsError(r.detail())
    }

    private class Result(val code: Int, val out: String) {
        fun detail(): String = out.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(MAX_DETAIL)
            ?: "exit code $code"
    }

    /** Throws [TsError] when the CLI cannot start or does not end in time. */
    private fun run(vararg args: String): Result {
        val exe = exe ?: throw TsError("not installed")
        val process = try {
            ProcessBuilder(listOf(exe.path) + args).redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(File(if (isWindows) "NUL" else "/dev/null")))
                .start()
        } catch (e: java.io.IOException) {
            throw TsError(e.message ?: "cannot run tailscale")
        }
        // Read on a thread of its own: a full pipe must not stall the CLI, and a hung child must not stall us.
        val out = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader(Charsets.UTF_8).use { r -> r.forEachLine { synchronized(out) { out.appendLine(it) } } } }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            throw TsError("timeout")
        }
        // The output ends with the process (a child left holding the pipe gets a moment, not more).
        reader.join(READ_AFTER_EXIT_MS)
        return Result(process.exitValue(), synchronized(out) { out.toString() })
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val READ_AFTER_EXIT_MS = 2_000L
        const val MAX_DETAIL = 300
        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }
}

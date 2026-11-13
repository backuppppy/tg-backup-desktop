package org.tgbackup.desktop.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import org.tgbackup.app.engine.LogBus
import org.tgbackup.desktop.DesktopPrefs
import org.tgbackup.desktop.server.REMOTE_PORT
import java.net.BindException

/**
 * The phone access for Settings: [enabled] is the switch; [state] one of `Off`, `NotInstalled`, `NotConnected`,
 * `HttpsOff`, `PortBusy`, `ServeBusy`, `Error` (with [detail]) or `Running` (with [url]).
 */
@Serializable
data class RemoteStatusDto(
    val enabled: Boolean,
    val state: String,
    val url: String? = null,
    val detail: String? = null,
    val devices: Int = 0,
    val hasPassword: Boolean,
)

/** Where the phone access stands, apart from the switch, the devices and the password. */
private data class Core(val state: String, val url: String? = null, val detail: String? = null)

private val OFF = Core("Off")

/** The Settings text of a state (also in the log). */
private fun stateText(c: Core): String = when (c.state) {
    "Off" -> "כבוי."
    "NotInstalled" -> "Tailscale לא מותקן במחשב."
    "NotConnected" -> "Tailscale מותקן אבל לא מחובר. פותחים את Tailscale ליד השעון ומתחברים."
    "HttpsOff" -> "צריך להפעיל https בחשבון Tailscale (שלב 2)."
    "PortBusy" -> "הפורט $REMOTE_PORT במחשב תפוס על ידי תוכנה אחרת."
    "ServeBusy" -> "ב-Tailscale כבר מוגדר שירות אחר על https. התוכנה לא דורסת אותו."
    "Error" -> "שגיאה מ-Tailscale: ${c.detail}"
    else -> "פועל. סורקים את הקוד בטלפון:"
}

/**
 * Keeps the phone access in line with the switch ([DesktopPrefs] `remoteAccess`). While it is on, every [pollMs]
 * ([runningPollMs] once running) it reads `tailscale status`; with https up and port 443 not someone else's, it
 * starts the phone's server ([startServer], given the tailnet names it may answer to) and adds our serve entry when
 * it is missing. Switching off stops the server and removes our serve entry only (never another's). Closing the
 * program ([close]) stops the server and leaves serve as is: it is ours again on the next launch.
 * CLI calls and the server's start and stop run on `Dispatchers.IO`, never on the caller's thread, and a cancellation
 * (switching off, closing) interrupts them.
 */
class RemoteAccessController(
    private val prefs: DesktopPrefs,
    private val auth: RemoteAuth,
    private val cli: TailscaleCli,
    private val startServer: (names: () -> Set<String>) -> AutoCloseable,
    private val log: LogBus,
    private val scope: CoroutineScope,
    private val pollMs: Long = 10_000,
    private val runningPollMs: Long = 60_000,
) : AutoCloseable {
    private val core = MutableStateFlow(OFF)
    private val hasPassword = MutableStateFlow(auth.hasPassword())

    /** The tailnet name the running server answers to. */
    @Volatile
    private var dnsName: String? = null

    private var server: AutoCloseable? = null

    val status: StateFlow<RemoteStatusDto> =
        combine(core, prefs.value.map { it.remoteAccess }, auth.devices, hasPassword) { c, on, devices, pw ->
            RemoteStatusDto(on, c.state, c.url, c.detail, devices, pw)
        }.stateIn(scope, SharingStarted.Eagerly, dto())

    private fun dto() = RemoteStatusDto(
        prefs.value.value.remoteAccess, core.value.state, core.value.url, core.value.detail,
        auth.devices.value, hasPassword.value,
    )

    /** Follows the switch from now on (DesktopApp.start). */
    fun start() {
        scope.launch {
            var wasOn = false
            prefs.value.map { it.remoteAccess }.distinctUntilChanged().collectLatest { on ->
                // collectLatest cancels the loop and waits for it (and its CLI call) before switching off.
                if (on) {
                    wasOn = true
                    while (true) {
                        val now = tick()
                        delay(if (now.state == "Running") runningPollMs else pollMs)
                    }
                } else if (wasOn) {
                    wasOn = false
                    turnOff()
                }
            }
        }
    }

    /** The switch. Refused (false) when turning on without a password. */
    fun setEnabled(on: Boolean): Boolean {
        if (on && !auth.hasPassword()) return false
        prefs.update { it.copy(remoteAccess = on) }
        return true
    }

    /** A new password (at least 6 characters); every device signs in again. Slow (PBKDF2): call it off the main thread. */
    fun setPassword(pw: String) {
        auth.setPassword(pw)
        hasPassword.value = auth.hasPassword()
    }

    fun disconnectAll() {
        auth.disconnectAll()
        log.info("כל המכשירים נותקו")
    }

    /** Program exit: the server stops, serve stays (see the class). */
    override fun close() {
        stopServer()
    }

    /** One look at Tailscale; returns the new state. */
    private suspend fun tick(): Core {
        val next = try {
            check()
        } catch (e: CancellationException) {
            throw e
        } catch (e: TsError) {
            Core("Error", detail = e.detail)
        } catch (e: Exception) {
            Core("Error", detail = e.message ?: e::class.java.simpleName)
        }
        if (next.state != "Running") io { stopServer() }
        publish(next)
        return next
    }

    private suspend fun check(): Core {
        val up = when (val st = io { cli.status() }) {
            TsStatus.NotInstalled -> return Core("NotInstalled")
            TsStatus.NotConnected -> return Core("NotConnected")
            is TsStatus.Failed -> return Core("Error", detail = st.detail)
            is TsStatus.Up -> st
        }
        if (!up.httpsOn) return Core("HttpsOff")
        val serve = io { cli.serveStatus(up.dnsName) }
        if (serve == ServeConfig.Busy) return Core("ServeBusy")
        dnsName = up.dnsName
        if (!serverRunning()) {
            try {
                io { synchronized(this) { server = startServer { dnsName?.let(::setOf).orEmpty() } } }
            } catch (e: BindException) {
                return Core("PortBusy")
            }
        }
        if (serve != ServeConfig.Ours) io { cli.serveOn(REMOTE_PORT) }
        return Core("Running", url = "https://${up.dnsName}")
    }

    /**
     * Switched off: the server stops and the state is Off at once; then our serve entry goes (only ours, under any
     * name), however long Tailscale takes.
     */
    private suspend fun turnOff() {
        io { stopServer() }
        publish(OFF)
        try {
            if (io { cli.serveStatus(null) } == ServeConfig.Ours) io { cli.serveOff() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val detail = (e as? TsError)?.detail ?: e.message ?: e::class.java.simpleName
            log.warn("גישה מהטלפון: ${stateText(Core("Error", detail = detail))}")
        }
    }

    /** Sets [next]; a change of state, url or detail goes to the log. */
    private fun publish(next: Core) {
        val before = core.value
        core.value = next
        if (before == next) return
        when (next.state) {
            "Running" -> log.info("הגישה מהטלפון פועלת: ${next.url}")
            "Off" -> log.info("הגישה מהטלפון כובתה")
            else -> log.warn("גישה מהטלפון: ${stateText(next)}")
        }
    }

    @Synchronized
    private fun serverRunning(): Boolean = server != null

    private fun stopServer() {
        val s = synchronized(this) { server.also { server = null } } ?: return
        runCatching { s.close() }
        dnsName = null
    }

    /** On IO; a cancellation interrupts the blocking call (ProcessTailscale kills the CLI then). */
    private suspend fun <T> io(block: () -> T): T = runInterruptible(Dispatchers.IO) { block() }
}

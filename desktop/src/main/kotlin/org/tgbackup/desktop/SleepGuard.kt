package org.tgbackup.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.Union
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.platform.win32.WinBase
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Asks the OS to stay awake (or stop asking). */
fun interface PowerRequests {
    fun keepAwake(on: Boolean)

    /** At launch: puts back what a crashed run left changed (see [SleepOverride.recover]). True when it did. */
    fun recover(): Boolean = false

    /** Every couple of seconds: dims the panel after the user's display timeout while [active], restores on input. */
    fun idleTick(active: Boolean) {}

    /** The panel is dimmed now (the caller then checks for input more often). */
    val isDimmed: Boolean get() = false

    companion object {
        /**
         * Windows: power requests plus, when [sleepOverride], the temporary "sleep after: never" saved in [dataDir]
         * (`--smoke` runs without it). [log] gets the user-facing lines of [SleepOverride]. Other systems: a no-op.
         */
        fun forCurrentOs(
            dataDir: File,
            sleepOverride: Boolean = true,
            log: (String) -> Unit = {},
            warn: (String) -> Unit = log,
        ): PowerRequests =
            if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
                WindowsPowerSettings().let { settings ->
                    // Only a Modern Standby PC sleeps when its screen turns off: an S3 PC keeps its screen's own timeout.
                    val modernStandby = runCatching { isModernStandby() }.getOrDefault(true)
                    WindowsPowerRequests(
                        if (sleepOverride) SleepOverride(File(dataDir, SleepOverride.FILE_NAME), settings, log, warn) else null,
                        if (sleepOverride && modernStandby) {
                            ScreenDimmer(File(dataDir, ScreenDimmer.FILE_NAME), WmiBrightness(), WindowsIdleClock(settings), log)
                        } else {
                            null
                        },
                    )
                }
            } else {
                PowerRequests { }
            }
    }
}

/**
 * Keeps the machine from sleeping while a backup job or an export runs (the desktop replacement for the
 * Android wake lock). Calls [PowerRequests.keepAwake] only when the state changes.
 */
class SleepGuard(private val power: PowerRequests) {
    /** The state the system is in; null after a failed call (some of its parts may have been done). */
    private var active: Boolean? = false

    /** The state the system is in (null: unknown after a failed call). */
    val state: Boolean?
        @Synchronized get() = active

    /** The state last asked for ([update]); [retry] sends it again under the same lock. */
    private var wanted = false

    /** Sends the last wanted state again when the system may not be in it (a call failed). */
    @Synchronized
    fun retry(): Boolean = update(wanted)

    /**
     * Returns true when the state changed (the call was made and succeeded). After a call that threw the state is
     * unknown, so the next update calls again, in either direction.
     */
    @Synchronized
    fun update(active: Boolean): Boolean {
        wanted = active
        if (active == this.active) return false
        try {
            power.keepAwake(active)
        } catch (t: Throwable) {
            this.active = null
            throw t
        }
        this.active = active
        return true
    }
}

/**
 * Power requests with a reason (`PowerCreateRequest`, shown by `powercfg /requests`): SystemRequired,
 * ExecutionRequired and DisplayRequired. A Modern Standby PC sleeps within seconds of its screen turning off, whatever
 * the other requests say, so the display is kept on and [dimmer] darkens the panel when nobody uses the PC. Also `SetThreadExecutionState`, which is
 * per thread, so every call runs on one dedicated daemon thread. Then [override] (Modern Standby PCs sleep despite
 * the requests): held while on, released when off. A failed call throws (with `GetLastError`); [SleepGuard] retries.
 */
internal class WindowsPowerRequests(
    private val override: SleepOverride?,
    /** Present on a Modern Standby PC: then the display is kept on too, and this dims it. */
    private val dimmer: ScreenDimmer? = null,
) : PowerRequests {
    private val requests = if (dimmer != null) REQUESTS else REQUESTS - POWER_REQUEST_DISPLAY_REQUIRED
    private val awakeState = ES_CONTINUOUS or ES_SYSTEM_REQUIRED or (if (dimmer != null) ES_DISPLAY_REQUIRED else 0)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tgb-power").apply { isDaemon = true }
    }
    private val kernel32: PowerKernel32 by lazy { Native.load("kernel32", PowerKernel32::class.java) }

    /** Kept for the life of the request (the reason string lives in its native memory). */
    private var context: ReasonContext? = null
    private var request: HANDLE? = null

    /** Whether our requests are set (PowerSetRequest counts: each set needs exactly one clear). */
    private var requestsSet = false

    override fun keepAwake(on: Boolean) {
        // The brightness comes back before the display request goes (PowerShell: off the power thread's 5 s bound).
        // A failed restore never keeps the requests or "sleep after: never": they are cleared, then it is thrown.
        val restored = if (on) Result.success(Unit) else runCatching { dimmer?.restore(); Unit }
        onPowerThread {
            if (on) {
                val h = request()
                if (!requestsSet) {
                    val done = mutableListOf<Int>()
                    for (type in requests) {
                        if (!kernel32.PowerSetRequest(h, type)) {
                            val error = Native.getLastError()
                            done.forEach { kernel32.PowerClearRequest(h, it) }
                            throw IllegalStateException("PowerSetRequest(${REQUEST_NAMES.getValue(type)}): error $error")
                        }
                        done += type
                    }
                    requestsSet = true
                }
                executionState(awakeState)
                override?.hold()
            } else {
                request?.takeIf { requestsSet }?.let { h ->
                    val failed = requests.mapNotNull { type ->
                        if (kernel32.PowerClearRequest(h, type)) null else "${REQUEST_NAMES.getValue(type)}: error ${Native.getLastError()}"
                    }
                    requestsSet = false
                    check(failed.isEmpty()) { "PowerClearRequest(${failed.joinToString()})" }
                }
                executionState(ES_CONTINUOUS)
                override?.release()
            }
        }
        restored.getOrThrow()
    }

    /** The sleep settings and the brightness are both put back; neither stops the other. */
    override fun recover(): Boolean {
        val dimmed = runCatching { dimmer?.recover() ?: false }
        val sleep = onPowerThread { override?.recover() ?: false }
        return dimmed.getOrThrow() or sleep
    }

    override fun idleTick(active: Boolean) {
        dimmer?.tick(active)
    }

    override val isDimmed: Boolean get() = dimmer?.isDimmed == true

    /** The one power request of this run, created on first use. */
    private fun request(): HANDLE {
        request?.let { return it }
        val ctx = ReasonContext(REASON)
        val h = kernel32.PowerCreateRequest(ctx)
        if (h == null || h == WinBase.INVALID_HANDLE_VALUE) {
            throw IllegalStateException("PowerCreateRequest: error ${Native.getLastError()}")
        }
        context = ctx
        request = h
        return h
    }

    /** `SetThreadExecutionState` returns the previous state, 0 on failure. */
    private fun executionState(flags: Int) {
        if (Kernel32.INSTANCE.SetThreadExecutionState(flags) == 0) {
            throw IllegalStateException("SetThreadExecutionState: error ${Native.getLastError()}")
        }
    }

    private fun win(call: String, ok: Boolean) {
        if (!ok) throw IllegalStateException("$call: error ${Native.getLastError()}")
    }

    private fun <T> onPowerThread(block: () -> T): T =
        try {
            executor.submit(Callable { block() }).get(5, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            throw IllegalStateException("פג הזמן (5 שניות)", e)
        }

    /** kernel32's power request calls (not in jna-platform). `GetLastError` is kept by JNA: [Native.getLastError]. */
    @Suppress("FunctionName")
    private interface PowerKernel32 : Library {
        fun PowerCreateRequest(context: ReasonContext): HANDLE?
        fun PowerSetRequest(request: HANDLE, type: Int): Boolean
        fun PowerClearRequest(request: HANDLE, type: Int): Boolean
    }

    private companion object {
        const val ES_CONTINUOUS = 0x80000000.toInt()
        const val ES_SYSTEM_REQUIRED = 0x1
        const val ES_DISPLAY_REQUIRED = 0x2
        const val POWER_REQUEST_DISPLAY_REQUIRED = 0
        const val POWER_REQUEST_SYSTEM_REQUIRED = 1
        const val POWER_REQUEST_EXECUTION_REQUIRED = 3
        val REQUESTS = listOf(POWER_REQUEST_SYSTEM_REQUIRED, POWER_REQUEST_EXECUTION_REQUIRED, POWER_REQUEST_DISPLAY_REQUIRED)
        val REQUEST_NAMES = mapOf(
            POWER_REQUEST_DISPLAY_REQUIRED to "DisplayRequired",
            POWER_REQUEST_SYSTEM_REQUIRED to "SystemRequired",
            POWER_REQUEST_EXECUTION_REQUIRED to "ExecutionRequired",
        )
        const val REASON = "TG Backup: משימה פעילה"
    }
}

/** `REASON_CONTEXT` with `POWER_REQUEST_CONTEXT_SIMPLE_STRING`: Version 0, Flags 1, the union's `SimpleReasonString`. */
@Structure.FieldOrder("Version", "Flags", "Reason")
internal class ReasonContext(reason: String) : Structure() {
    @JvmField var Version: Int = POWER_REQUEST_CONTEXT_VERSION
    @JvmField var Flags: Int = POWER_REQUEST_CONTEXT_SIMPLE_STRING
    @JvmField var Reason: ReasonUnion = ReasonUnion()

    init {
        Reason.SimpleReasonString = WString(reason)
        Reason.setType("SimpleReasonString")
        write()
    }

    /** The union of `REASON_CONTEXT`: the detailed (localized) form or one string. Only the string is used. */
    class ReasonUnion : Union() {
        @JvmField var Detailed: DetailedReason = DetailedReason()
        @JvmField var SimpleReasonString: WString? = null
    }

    @FieldOrder("LocalizedReasonModule", "LocalizedReasonId", "ReasonStringCount", "ReasonStrings")
    class DetailedReason : Structure() {
        @JvmField var LocalizedReasonModule: Pointer? = null
        @JvmField var LocalizedReasonId: Int = 0
        @JvmField var ReasonStringCount: Int = 0
        @JvmField var ReasonStrings: Pointer? = null
    }

    private companion object {
        const val POWER_REQUEST_CONTEXT_VERSION = 0
        const val POWER_REQUEST_CONTEXT_SIMPLE_STRING = 0x1
    }
}

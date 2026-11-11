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
                WindowsPowerRequests(
                    if (sleepOverride) SleepOverride(File(dataDir, SleepOverride.FILE_NAME), WindowsPowerSettings(), log, warn) else null,
                )
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
 * Power requests with a reason (`PowerCreateRequest`, shown by `powercfg /requests`): SystemRequired and
 * ExecutionRequired, never DisplayRequired, so the screen still turns off. Also `SetThreadExecutionState`, which is
 * per thread, so every call runs on one dedicated daemon thread. Then [override] (Modern Standby PCs sleep despite
 * the requests): held while on, released when off. A failed call throws (with `GetLastError`); [SleepGuard] retries.
 */
internal class WindowsPowerRequests(private val override: SleepOverride?) : PowerRequests {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tgb-power").apply { isDaemon = true }
    }
    private val kernel32: PowerKernel32 by lazy { Native.load("kernel32", PowerKernel32::class.java) }

    /** Kept for the life of the request (the reason string lives in its native memory). */
    private var context: ReasonContext? = null
    private var request: HANDLE? = null

    /** Whether our requests are set (PowerSetRequest counts: each set needs exactly one clear). */
    private var requestsSet = false

    override fun keepAwake(on: Boolean): Unit = onPowerThread {
        if (on) {
            val h = request()
            if (!requestsSet) {
                win("PowerSetRequest(SystemRequired)", kernel32.PowerSetRequest(h, POWER_REQUEST_SYSTEM_REQUIRED))
                if (!kernel32.PowerSetRequest(h, POWER_REQUEST_EXECUTION_REQUIRED)) {
                    val error = Native.getLastError()
                    kernel32.PowerClearRequest(h, POWER_REQUEST_SYSTEM_REQUIRED)
                    throw IllegalStateException("PowerSetRequest(ExecutionRequired): error $error")
                }
                requestsSet = true
            }
            executionState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED)
            override?.hold()
        } else {
            request?.takeIf { requestsSet }?.let { h ->
                val system = kernel32.PowerClearRequest(h, POWER_REQUEST_SYSTEM_REQUIRED)
                val systemError = Native.getLastError()
                val execution = kernel32.PowerClearRequest(h, POWER_REQUEST_EXECUTION_REQUIRED)
                val executionError = Native.getLastError()
                requestsSet = false
                check(system) { "PowerClearRequest(SystemRequired): error $systemError" }
                check(execution) { "PowerClearRequest(ExecutionRequired): error $executionError" }
            }
            executionState(ES_CONTINUOUS)
            override?.release()
        }
    }

    override fun recover(): Boolean = onPowerThread { override?.recover() ?: false }

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
        const val POWER_REQUEST_SYSTEM_REQUIRED = 1
        const val POWER_REQUEST_EXECUTION_REQUIRED = 3
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

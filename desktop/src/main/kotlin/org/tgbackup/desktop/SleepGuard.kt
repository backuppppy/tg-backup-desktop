package org.tgbackup.desktop

import com.sun.jna.platform.win32.Kernel32
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Asks the OS to stay awake (or stop asking). */
fun interface PowerRequests {
    fun keepAwake(on: Boolean)

    companion object {
        fun forCurrentOs(): PowerRequests =
            if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
                WindowsPowerRequests()
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
    private var active = false

    @Synchronized
    fun update(active: Boolean) {
        if (active == this.active) return
        this.active = active
        power.keepAwake(active)
    }
}

/**
 * `SetThreadExecutionState` is per thread, so the request and its release must come from the same thread:
 * every call runs on one dedicated daemon thread. The screen may still turn off (no ES_DISPLAY_REQUIRED).
 */
internal class WindowsPowerRequests : PowerRequests {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tgb-power").apply { isDaemon = true }
    }

    override fun keepAwake(on: Boolean) {
        val flags = if (on) ES_CONTINUOUS or ES_SYSTEM_REQUIRED else ES_CONTINUOUS
        executor.submit { Kernel32.INSTANCE.SetThreadExecutionState(flags) }.get(5, TimeUnit.SECONDS)
    }

    private companion object {
        const val ES_CONTINUOUS = 0x80000000.toInt()
        const val ES_SYSTEM_REQUIRED = 0x1
    }
}

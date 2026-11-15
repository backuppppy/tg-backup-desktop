package org.tgbackup.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The built-in panel's brightness through WMI (`root/WMI` WmiMonitorBrightness), by a hidden PowerShell: only laptop
 * panels have it, so a PC with only external monitors reads null and is never dimmed.
 */
internal class WmiBrightness(private val timeoutMs: Long = 10_000) : Brightness {
    override fun read(): Int? = runCatching {
        val r = powershell(
            "(Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightness -ErrorAction Stop | " +
                "Select-Object -First 1).CurrentBrightness",
        )
        if (r.first != 0) null else r.second.trim().lines().lastOrNull()?.trim()?.toIntOrNull()
    }.getOrNull()

    override fun set(level: Int) {
        val r = powershell(
            "Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightnessMethods -ErrorAction Stop | " +
                "Invoke-CimMethod -MethodName WmiSetBrightness -Arguments @{Timeout=0; Brightness=${level.coerceIn(0, 100)}} -ErrorAction Stop",
        )
        check(r.first == 0) { "WmiSetBrightness($level): ${r.second.trim().take(200)}" }
    }

    /** Runs one PowerShell command; returns its exit code and output (stderr merged). Java starts it without a window. */
    private fun powershell(command: String): Pair<Int, String> {
        val root = System.getenv("SystemRoot") ?: "C:\\Windows"
        val exe = File(root, "System32\\WindowsPowerShell\\v1.0\\powershell.exe").takeIf { it.exists() }?.path ?: "powershell.exe"
        val process = ProcessBuilder(exe, "-NoProfile", "-NonInteractive", "-Command", command)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("NUL")))
            .start()
        val out = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().use { r -> r.forEachLine { synchronized(out) { out.appendLine(it) } } } }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("PowerShell: timeout")
        }
        reader.join(1_000)
        return process.exitValue() to synchronized(out) { out.toString() }
    }
}

/** Idle time from `GetLastInputInfo`, and VIDEOIDLE of the active scheme for the current power source. */
internal class WindowsIdleClock(private val settings: WindowsPowerSettings) : IdleClock {
    private val power: PowerStatusKernel32 by lazy { Native.load("kernel32", PowerStatusKernel32::class.java) }
    override fun idleMs(): Long {
        val info = WinUser.LASTINPUTINFO()
        if (!User32.INSTANCE.GetLastInputInfo(info)) return 0
        // Both are 32-bit tick counts that wrap after 49.7 days: the difference is taken unsigned.
        return (Kernel32.INSTANCE.GetTickCount() - info.dwTime).toLong() and 0xFFFFFFFFL
    }

    override fun displayTimeoutMs(): Long {
        val status = PowerStatus()
        // ACLineStatus: 0 offline (battery), 1 online, 255 unknown (counted as AC).
        val onAc = !power.GetSystemPowerStatus(status) || status.ACLineStatus.toInt() != 0
        return settings.readVideoIdle(onAc).coerceAtLeast(0) * 1000L
    }
}

/** `SYSTEM_POWER_STATUS` (not in this jna-platform). */
@Structure.FieldOrder(
    "ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime",
)
internal class PowerStatus : Structure() {
    @JvmField var ACLineStatus: Byte = 0
    @JvmField var BatteryFlag: Byte = 0
    @JvmField var BatteryLifePercent: Byte = 0
    @JvmField var SystemStatusFlag: Byte = 0
    @JvmField var BatteryLifeTime: Int = 0
    @JvmField var BatteryFullLifeTime: Int = 0
}

@Suppress("FunctionName")
private interface PowerStatusKernel32 : Library {
    fun GetSystemPowerStatus(status: PowerStatus): Boolean
}

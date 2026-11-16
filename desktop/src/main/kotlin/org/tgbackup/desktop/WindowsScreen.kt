package org.tgbackup.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
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

    override fun presenting(): Boolean = runCatching {
        val state = IntByReference()
        shell.SHQueryUserNotificationState(state) == 0 && isPresentingState(state.value)
    }.getOrDefault(false)

    private val shell: Shell32Quns by lazy { Native.load("shell32", Shell32Quns::class.java) }
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

@Suppress("FunctionName")
private interface Shell32Quns : Library {
    fun SHQueryUserNotificationState(state: IntByReference): Int
}

/**
 * Whether this PC uses Modern Standby (S0 low power idle), from `SYSTEM_POWER_CAPABILITIES`: `AoAc` (byte 20) is set,
 * or S3 (byte 5) is missing. Unknown counts as yes (keeping the display on is the safe side).
 */
internal fun isModernStandby(): Boolean {
    val buf = com.sun.jna.Memory(SYSTEM_POWER_CAPABILITIES_SIZE.toLong()).apply { clear() }
    val rc = Native.load("powrprof", PowrProfCaps::class.java)
        .CallNtPowerInformation(SYSTEM_POWER_CAPABILITIES, null, 0, buf, SYSTEM_POWER_CAPABILITIES_SIZE)
    if (rc != 0) return true
    return buf.getByte(AOAC_OFFSET).toInt() != 0 || buf.getByte(S3_OFFSET).toInt() == 0
}

private const val SYSTEM_POWER_CAPABILITIES = 4
private const val SYSTEM_POWER_CAPABILITIES_SIZE = 76
private const val S3_OFFSET = 5L
private const val AOAC_OFFSET = 20L

@Suppress("FunctionName")
private interface PowrProfCaps : Library {
    fun CallNtPowerInformation(level: Int, input: com.sun.jna.Pointer?, inputLength: Int, output: com.sun.jna.Pointer, outputLength: Int): Int
}

/**
 * QUERY_USER_NOTIFICATION_STATE: QUNS_BUSY (2, a full-screen app), QUNS_RUNNING_D3D_FULL_SCREEN (3) or
 * QUNS_PRESENTATION_MODE (4). 5 (QUNS_ACCEPTS_NOTIFICATIONS) is the normal desktop.
 */
internal fun isPresentingState(state: Int): Boolean = state in 2..4

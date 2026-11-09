package org.tgbackup.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/** The program's value under the user's Run key: what Windows runs at sign-in. */
interface RunKey {
    fun get(): String?
    fun set(command: String)
    fun remove()
}

/**
 * Starts the program with Windows (the settings switch): the Run value is `"<exe>" --minimized`, so it starts
 * without a window. With no [exe] (not an installed build, or not Windows) nothing is touched.
 */
class Autostart(private val key: RunKey, private val exe: String?) {
    /** Writes the value when [on] and it differs (a moved exe is rewritten); removes it when off and present. */
    fun apply(on: Boolean) {
        if (exe.isNullOrBlank()) return
        if (on) {
            val command = "\"$exe\" --minimized"
            if (key.get() != command) key.set(command)
        } else if (key.get() != null) {
            key.remove()
        }
    }

    companion object {
        /** Never touches the Run key (`--smoke`: its throw-away prefs must not switch the user's real value off). */
        val NONE: Autostart = Autostart(NoRunKey, null)

        /** [NONE] under `--smoke`, otherwise [real] (the Run key on Windows). */
        fun forLaunch(smoke: Boolean, real: () -> Autostart = ::forCurrentOs): Autostart = if (smoke) NONE else real()

        /** The Run key on Windows with the jpackage launcher's path; elsewhere a no-op. */
        fun forCurrentOs(): Autostart =
            if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
                Autostart(WindowsRunKey(), System.getProperty("jpackage.app-path"))
            } else {
                Autostart(NoRunKey, null)
            }
    }
}

private object NoRunKey : RunKey {
    override fun get(): String? = null
    override fun set(command: String) {}
    override fun remove() {}
}

/** `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, value `TGBackup`. */
internal class WindowsRunKey : RunKey {
    override fun get(): String? =
        if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN, NAME)) {
            Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, RUN, NAME)
        } else {
            null
        }

    override fun set(command: String) = Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN, NAME, command)

    override fun remove() = Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN, NAME)

    private companion object {
        const val RUN = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val NAME = "TGBackup"
    }
}

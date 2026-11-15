package org.tgbackup.desktop

import org.tgbackup.io.AtomicFiles
import java.io.File

/** The built-in panel's brightness (0-100). [read] is null when there is no such panel; [set] throws on failure. */
interface Brightness {
    fun read(): Int?
    fun set(level: Int)
}

/** How long the PC has had no input, and the user's own "turn off the display after" (0 = never), in ms. */
interface IdleClock {
    fun idleMs(): Long
    fun displayTimeoutMs(): Long
}

/**
 * While work runs the display is kept on (a Modern Standby PC sleeps when it turns off), so instead of turning off
 * after the user's display timeout the panel dims to 0; input or the end of the work brings the saved level back.
 * The level to restore is kept in [file] until restored, so a crash is undone at the next launch ([recover]).
 */
class ScreenDimmer(
    private val file: File,
    private val brightness: Brightness,
    private val idle: IdleClock,
    private val log: (String) -> Unit = {},
) {
    private var dimmed = file.exists()
    private var noPanelLogged = false

    @Synchronized
    fun tick(active: Boolean) {
        val timeout = idle.displayTimeoutMs()
        if (dimmed) {
            if (!active || timeout <= 0 || idle.idleMs() < timeout) restore()
            return
        }
        if (!active || timeout <= 0 || idle.idleMs() < timeout) return
        val level = brightness.read()
        if (level == null) {
            if (!noPanelLogged) log("אין מסך מובנה שאפשר להחשיך — המסך נשאר דלוק בזמן שמשימה רצה")
            noPanelLogged = true
            return
        }
        save(level)
        dimmed = true
        brightness.set(0)
    }

    /** Puts the saved level back, then forgets it. True when it restored. */
    @Synchronized
    fun restore(): Boolean {
        if (!file.exists()) {
            dimmed = false
            return false
        }
        val level = file.readText().trim().toIntOrNull()
        if (level != null) brightness.set(level)
        file.delete()
        dimmed = false
        return level != null
    }

    fun recover(): Boolean = restore()

    private fun save(level: Int) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(level.toString())
        AtomicFiles.replace(tmp, file)
    }

    companion object {
        const val FILE_NAME = "dimmed-brightness.txt"
    }
}

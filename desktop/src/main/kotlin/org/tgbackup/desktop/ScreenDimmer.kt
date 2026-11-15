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

    /** A full-screen app or a presentation is showing (never dimmed under it). */
    fun presenting(): Boolean = false
}

/**
 * While work runs the display is kept on (a Modern Standby PC sleeps when it turns off), so instead of turning off
 * after the user's display timeout the panel dims to 0; input or the end of the work brings the saved level back.
 * The level to restore is kept in [file] until restored, so a crash is undone at the next launch ([recover]).
 * A failed brightness call waits [RETRY_MS] before the next try from [tick] (each try starts a PowerShell).
 */
class ScreenDimmer(
    private val file: File,
    private val brightness: Brightness,
    private val idle: IdleClock,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var dimmed = file.exists()

    /** No built-in panel was found: not asked again until the work stops. */
    private var noPanel = false
    private var noPanelLogged = false
    private var retryAt = 0L

    val isDimmed: Boolean
        @Synchronized get() = dimmed

    @Synchronized
    fun tick(active: Boolean) {
        if (!active) {
            noPanel = false
            if (dimmed && now() >= retryAt) backingOff { restore() }
            return
        }
        if (dimmed) {
            // Input first: a failing timeout read must not keep the screen dark.
            val timeout = runCatching { idle.displayTimeoutMs() }.getOrDefault(0)
            if ((timeout <= 0 || idle.idleMs() < timeout) && now() >= retryAt) backingOff { restore() }
            return
        }
        if (noPanel || now() < retryAt) return
        val timeout = idle.displayTimeoutMs()
        if (timeout <= 0 || idle.idleMs() < timeout || idle.presenting()) return
        val level = brightness.read()
        if (level == null) {
            noPanel = true
            if (!noPanelLogged) log("אין מסך מובנה שאפשר להחשיך — המסך נשאר דלוק בזמן שמשימה רצה")
            noPanelLogged = true
            return
        }
        // Already dark: nothing to save (a 0 must never become "the user's brightness").
        if (level <= 0) return
        save(level)
        dimmed = true
        try {
            brightness.set(0)
        } catch (e: Exception) {
            file.delete()
            dimmed = false
            retryAt = now() + RETRY_MS
            throw e
        }
    }

    /** Puts the saved level back (100 when the file is unreadable), then forgets it. True when it restored. */
    @Synchronized
    fun restore(): Boolean {
        if (!file.exists()) {
            dimmed = false
            return false
        }
        val level = runCatching { file.readText().trim().toInt() }.getOrNull()?.takeIf { it in 1..100 } ?: run {
            log("$FILE_NAME פגום — הבהירות הוחזרה ל-100")
            100
        }
        brightness.set(level)
        file.delete()
        dimmed = false
        return true
    }

    fun recover(): Boolean = restore()

    private inline fun backingOff(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            retryAt = now() + RETRY_MS
            throw e
        }
    }

    private fun save(level: Int) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(level.toString())
        AtomicFiles.replace(tmp, file)
    }

    companion object {
        const val FILE_NAME = "dimmed-brightness.txt"
        const val RETRY_MS = 60_000L
    }
}

package org.tgbackup.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tgbackup.io.AtomicFiles
import java.io.File

/**
 * The desktop's own settings: start with Windows (default off), keep the PC awake while work runs (default on) and
 * the phone access through Tailscale (default off).
 */
@Serializable
data class DesktopPrefsData(val autostart: Boolean = false, val keepAwake: Boolean = true, val remoteAccess: Boolean = false)

/** [DesktopPrefsData] in `desktop.json` under [dir]. A missing or unreadable file gives the defaults. */
class DesktopPrefs(private val dir: File) {
    private val file = File(dir, FILE_NAME)
    private val state = MutableStateFlow(load())

    val value: StateFlow<DesktopPrefsData> = state.asStateFlow()

    /** Saves [f] of the current value (temp file moved over `desktop.json`), then publishes it. */
    @Synchronized
    fun update(f: (DesktopPrefsData) -> DesktopPrefsData) {
        val next = f(state.value)
        if (next == state.value) return
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(DesktopPrefsData.serializer(), next))
        AtomicFiles.replace(tmp, file)
        state.value = next
    }

    private fun load(): DesktopPrefsData =
        runCatching { json.decodeFromString(DesktopPrefsData.serializer(), file.readText()) }.getOrDefault(DesktopPrefsData())

    private companion object {
        const val FILE_NAME = "desktop.json"
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    }
}

package org.tgbackup.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tgbackup.io.AtomicFiles
import java.io.File

/**
 * Windows' "sleep after" and "hibernate after" of the active power scheme, in seconds (0 = never), on mains (ac) and
 * on battery (dc). A negative value: the setting does not exist on this PC (it is never written).
 */
@Serializable
data class SleepTimeouts(val acStandby: Int, val dcStandby: Int, val acHibernate: Int, val dcHibernate: Int)

/** Reads and writes [SleepTimeouts] of the active power scheme. The "turn off the display" timeout is never touched. */
interface PowerSettings {
    fun read(): SleepTimeouts
    fun write(t: SleepTimeouts)
}

/**
 * A temporary "sleep after: never" while work runs (Modern Standby PCs sleep even with an active power request).
 * [hold] saves the user's values to [file] (`power-restore.json` in the data dir) before the first change, so they come
 * back on [release] (work ended), on [recover] at the next launch after a crash, and on uninstall (`--restore-power`).
 */
class SleepOverride(
    private val file: File,
    private val settings: PowerSettings,
    private val log: (String) -> Unit = {},
) {
    /** Saves the current values once (only when no saved file exists), then writes "never" to all of them. */
    @Synchronized
    fun hold() {
        val saved = if (file.exists()) {
            load()
        } else {
            settings.read().also(::save)
        }
        settings.write(saved.never())
    }

    /** Writes the saved values back and deletes the file. False when nothing was held. */
    @Synchronized
    fun release(): Boolean {
        if (!file.exists()) return false
        val saved = runCatching { load() }.getOrElse { e ->
            // An unreadable file can never be restored: drop it, so it does not fail every launch.
            file.delete()
            throw IllegalStateException("power-restore.json unreadable: ${e.message}", e)
        }
        settings.write(saved)
        file.delete()
        log(MSG_RESTORED)
        return true
    }

    /** At launch (and on uninstall): restores what a crashed run left overridden. */
    fun recover(): Boolean = release()

    private fun load(): SleepTimeouts = json.decodeFromString(SleepTimeouts.serializer(), file.readText())

    private fun save(t: SleepTimeouts) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(SleepTimeouts.serializer(), t))
        AtomicFiles.replace(tmp, file)
    }

    companion object {
        const val FILE_NAME = "power-restore.json"
        const val MSG_RESTORED = "הגדרת השינה של Windows הוחזרה למצבה הקודם"
        private val json = Json { ignoreUnknownKeys = true }

        /** "Never" for every setting that exists on this PC. */
        private fun SleepTimeouts.never() = SleepTimeouts(
            acStandby = acStandby.coerceAtMost(0),
            dcStandby = dcStandby.coerceAtMost(0),
            acHibernate = acHibernate.coerceAtMost(0),
            dcHibernate = dcHibernate.coerceAtMost(0),
        )
    }
}

/**
 * [PowerSettings] through powrprof on the active scheme (what `powercfg /change standby-timeout-ac` changes). A setting
 * the scheme does not have reads as -1 and is skipped on write.
 */
internal class WindowsPowerSettings : PowerSettings {
    private val powrprof: PowrProf = Native.load("powrprof", PowrProf::class.java)

    override fun read(): SleepTimeouts = withActiveScheme { scheme ->
        SleepTimeouts(
            acStandby = readIndex(scheme, STANDBY, ac = true),
            dcStandby = readIndex(scheme, STANDBY, ac = false),
            acHibernate = readIndex(scheme, HIBERNATE, ac = true),
            dcHibernate = readIndex(scheme, HIBERNATE, ac = false),
        )
    }

    override fun write(t: SleepTimeouts) = withActiveScheme { scheme ->
        writeIndex(scheme, STANDBY, ac = true, t.acStandby)
        writeIndex(scheme, STANDBY, ac = false, t.dcStandby)
        writeIndex(scheme, HIBERNATE, ac = true, t.acHibernate)
        writeIndex(scheme, HIBERNATE, ac = false, t.dcHibernate)
        // The written values take effect only once the scheme is applied again.
        check("PowerSetActiveScheme", powrprof.PowerSetActiveScheme(null, scheme))
    }

    private fun <T> withActiveScheme(block: (GUID) -> T): T {
        val ref = PointerByReference()
        check("PowerGetActiveScheme", powrprof.PowerGetActiveScheme(null, ref))
        try {
            return block(GUID(ref.value))
        } finally {
            Kernel32.INSTANCE.LocalFree(ref.value)
        }
    }

    private fun readIndex(scheme: GUID, setting: GUID, ac: Boolean): Int {
        val value = IntByReference()
        val rc = if (ac) {
            powrprof.PowerReadACValueIndex(null, scheme, SUBGROUP_SLEEP, setting, value)
        } else {
            powrprof.PowerReadDCValueIndex(null, scheme, SUBGROUP_SLEEP, setting, value)
        }
        if (rc == ERROR_FILE_NOT_FOUND) return -1
        check(if (ac) "PowerReadACValueIndex" else "PowerReadDCValueIndex", rc)
        return value.value
    }

    private fun writeIndex(scheme: GUID, setting: GUID, ac: Boolean, seconds: Int) {
        if (seconds < 0) return
        val rc = if (ac) {
            powrprof.PowerWriteACValueIndex(null, scheme, SUBGROUP_SLEEP, setting, seconds)
        } else {
            powrprof.PowerWriteDCValueIndex(null, scheme, SUBGROUP_SLEEP, setting, seconds)
        }
        check(if (ac) "PowerWriteACValueIndex" else "PowerWriteDCValueIndex", rc)
    }

    private fun check(call: String, rc: Int) {
        if (rc != 0) throw IllegalStateException("$call: error $rc")
    }

    /** powrprof.dll: each call returns a Win32 error code (0 = success). `RootPowerKey` is always null. */
    @Suppress("FunctionName")
    private interface PowrProf : Library {
        fun PowerGetActiveScheme(userRootPowerKey: Pointer?, activePolicyGuid: PointerByReference): Int
        fun PowerSetActiveScheme(userRootPowerKey: Pointer?, schemeGuid: GUID): Int
        fun PowerReadACValueIndex(root: Pointer?, scheme: GUID, subGroup: GUID, setting: GUID, value: IntByReference): Int
        fun PowerReadDCValueIndex(root: Pointer?, scheme: GUID, subGroup: GUID, setting: GUID, value: IntByReference): Int
        fun PowerWriteACValueIndex(root: Pointer?, scheme: GUID, subGroup: GUID, setting: GUID, value: Int): Int
        fun PowerWriteDCValueIndex(root: Pointer?, scheme: GUID, subGroup: GUID, setting: GUID, value: Int): Int
    }

    private companion object {
        const val ERROR_FILE_NOT_FOUND = 2
        val SUBGROUP_SLEEP: GUID = GUID.fromString("{238C9FA8-0AAD-41ED-83F4-97BE242C8F20}")
        val STANDBY: GUID = GUID.fromString("{29F6C1DB-86DA-48C5-9FDB-F2B67B1F44DA}")
        val HIBERNATE: GUID = GUID.fromString("{9D7815A6-7EE4-497E-8888-515A05F02364}")
    }
}

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
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Windows' "sleep after" and "hibernate after" of the active power scheme, in seconds (0 = never), on mains (ac) and
 * on battery (dc). A negative value: the setting does not exist on this PC (it is never written).
 */
@Serializable
data class SleepTimeouts(val acStandby: Int, val dcStandby: Int, val acHibernate: Int, val dcHibernate: Int)

/**
 * `power-restore.json`: the user's [SleepTimeouts] and the power scheme (its GUID) that was active when they were
 * saved. A file from an older version has no [scheme]: it is restored to the active scheme.
 */
@Serializable
internal data class SavedPower(
    val acStandby: Int,
    val dcStandby: Int,
    val acHibernate: Int,
    val dcHibernate: Int,
    val scheme: String? = null,
) {
    val timeouts get() = SleepTimeouts(acStandby, dcStandby, acHibernate, dcHibernate)
}

/** Reads and writes [SleepTimeouts] of a power scheme. The "turn off the display" timeout is never touched. */
interface PowerSettings {
    /** The active power scheme's GUID. */
    fun activeScheme(): String

    /** The active scheme's values. */
    fun read(): SleepTimeouts

    /** Writes to [scheme] (a GUID) when it still exists on this PC, otherwise (or when null) to the active scheme. */
    fun write(t: SleepTimeouts, scheme: String? = null)
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
    private val warn: (String) -> Unit = log,
) {
    /**
     * Saves the current values and the active scheme once (only when no saved file exists), then writes "never" to
     * all of them.
     */
    @Synchronized
    fun hold() {
        val saved = loadOrSetAside()?.timeouts ?: settings.read().also { save(it, settings.activeScheme()) }
        settings.write(saved.never())
    }

    /**
     * Writes the saved values back, to the scheme they were saved from (to the active one when that scheme is gone),
     * and deletes the file. False when nothing was held.
     */
    @Synchronized
    fun release(): Boolean {
        val saved = loadOrSetAside() ?: return false
        settings.write(saved.timeouts, saved.scheme)
        file.delete()
        log(MSG_RESTORED)
        return true
    }

    /** At launch (and on uninstall): restores what a crashed run left overridden. */
    fun recover(): Boolean = release()

    /**
     * The saved values, or null when there is no file. An unreadable file is kept as `power-restore.json.bad` (never
     * deleted) with a warning, and counts as none, so it does not fail every launch and [hold] saves afresh.
     */
    private fun loadOrSetAside(): SavedPower? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(SavedPower.serializer(), file.readText()) }.getOrElse { e ->
            val bad = File(file.parentFile, "${file.name}.bad")
            Files.move(file.toPath(), bad.toPath(), StandardCopyOption.REPLACE_EXISTING)
            warn("$FILE_NAME פגום ונשמר בשם ${bad.name}: ${e.message}")
            null
        }
    }

    private fun save(t: SleepTimeouts, scheme: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val saved = SavedPower(t.acStandby, t.dcStandby, t.acHibernate, t.dcHibernate, scheme)
        tmp.writeText(json.encodeToString(SavedPower.serializer(), saved))
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
 * [PowerSettings] through powrprof (what `powercfg /change standby-timeout-ac` changes on the active scheme). A setting
 * the scheme does not have reads as -1 and is skipped on write. GUIDs are lower case, without braces (as powercfg
 * prints them).
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

    override fun activeScheme(): String = withActiveScheme { it.text() }

    override fun write(t: SleepTimeouts, scheme: String?) = withActiveScheme { active ->
        val target = scheme?.let { s -> schemes().firstOrNull { it.text() == s.trim('{', '}').lowercase() } } ?: active
        writeIndex(target, STANDBY, ac = true, t.acStandby)
        writeIndex(target, STANDBY, ac = false, t.dcStandby)
        writeIndex(target, HIBERNATE, ac = true, t.acHibernate)
        writeIndex(target, HIBERNATE, ac = false, t.dcHibernate)
        // Values written to the active scheme take effect only once it is applied again (another scheme's wait
        // until it is chosen; the user's choice of scheme is never changed).
        check("PowerSetActiveScheme", powrprof.PowerSetActiveScheme(null, active))
    }

    /** Every power scheme on this PC. */
    private fun schemes(): List<GUID> = buildList {
        var index = 0
        while (true) {
            val buffer = ByteArray(16)
            val size = IntByReference(buffer.size)
            val rc = powrprof.PowerEnumerate(null, null, null, ACCESS_SCHEME, index++, buffer, size)
            if (rc == ERROR_NO_MORE_ITEMS) break
            check("PowerEnumerate", rc)
            add(GUID(buffer))
        }
    }

    private fun GUID.text(): String = toGuidString().trim('{', '}').lowercase()

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
        fun PowerEnumerate(
            root: Pointer?, scheme: Pointer?, subGroup: Pointer?, accessFlags: Int, index: Int,
            buffer: ByteArray, bufferSize: IntByReference,
        ): Int
        fun PowerReadACValueIndex(root: Pointer?, scheme: GUID, subGroup: GUID, setting: GUID, value: IntByReference): Int
        fun PowerReadDCValueIndex(root: Pointer?, scheme: GUID, subGroup: GUID, setting: GUID, value: IntByReference): Int
        fun PowerWriteACValueIndex(root: Pointer?, scheme: GUID, subGroup: GUID, setting: GUID, value: Int): Int
        fun PowerWriteDCValueIndex(root: Pointer?, scheme: GUID, subGroup: GUID, setting: GUID, value: Int): Int
    }

    private companion object {
        const val ERROR_FILE_NOT_FOUND = 2
        const val ERROR_NO_MORE_ITEMS = 259
        const val ACCESS_SCHEME = 16
        val SUBGROUP_SLEEP: GUID = GUID.fromString("{238C9FA8-0AAD-41ED-83F4-97BE242C8F20}")
        val STANDBY: GUID = GUID.fromString("{29F6C1DB-86DA-48C5-9FDB-F2B67B1F44DA}")
        val HIBERNATE: GUID = GUID.fromString("{9D7815A6-7EE4-497E-8888-515A05F02364}")
    }
}

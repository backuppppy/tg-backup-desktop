package org.tgbackup.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * TDLib's JVM library copies its DLLs into a fresh directory under `java.io.tmpdir` on every start and
 * loads them from there. On Windows that is `%TEMP%`, which sits under the user name, so a Hebrew user
 * name gives a Hebrew path. [apply] points `java.io.tmpdir` at an ASCII directory that belongs to
 * TG Backup, before anything creates a temp file.
 */
object NativeTmp {
    private val STALE_AFTER: Duration = Duration.ofHours(1)

    /**
     * Windows: `<data dir>\tmp` when that path is ASCII, otherwise `%ProgramData%\TGBackup-<user hash>\tmp`
     * (one per user, since ProgramData is shared). Returns null to keep the JVM default: on other systems,
     * or when no ASCII candidate is writable.
     */
    fun choose(
        env: Map<String, String> = System.getenv(),
        home: String = System.getProperty("user.home"),
        os: String = System.getProperty("os.name"),
        writable: (Path) -> Boolean = ::isWritableDir,
    ): Path? {
        if (!os.startsWith("Windows", ignoreCase = true)) return null
        val dataDir = AppDirs.dataDir(env, home, os).toString()
        val programData = (env["ProgramData"]?.takeIf { it.isNotBlank() } ?: "C:\\ProgramData").trimEnd('\\', '/')
        return listOf(
            "$dataDir\\tmp",
            "$programData\\TGBackup-${hash8(dataDir)}\\tmp",
        ).asSequence()
            .filter(::isAscii)
            .map { Paths.get(it) }
            .firstOrNull(writable)
    }

    /** Sets `java.io.tmpdir` to [choose]'s directory and removes leftovers of earlier runs. */
    fun apply(): Path? {
        val dir = choose() ?: return null
        System.setProperty("java.io.tmpdir", dir.toString())
        cleanStale(dir)
        return dir
    }

    /**
     * The library's `deleteOnExit` cannot remove DLLs that are still loaded, so each run leaves a
     * directory behind. Deletes entries older than [STALE_AFTER]; locked files are skipped.
     */
    fun cleanStale(dir: Path, now: Instant = Instant.now()) {
        val cutoff = now.minus(STALE_AFTER)
        val entries = runCatching { Files.list(dir).use { it.toList() } }.getOrDefault(emptyList())
        for (entry in entries) {
            val modified = runCatching { Files.getLastModifiedTime(entry).toInstant() }.getOrNull() ?: continue
            if (modified.isAfter(cutoff)) continue
            runCatching { entry.toFile().deleteRecursively() }
        }
    }

    private fun isAscii(s: String): Boolean = s.all { it in ' '..'~' }

    private fun hash8(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.lowercase().toByteArray())
            .take(4).joinToString("") { "%02x".format(it) }

    private fun isWritableDir(dir: Path): Boolean = runCatching {
        Files.createDirectories(dir)
        Files.delete(Files.createTempFile(dir, "probe", ".tmp"))
        true
    }.getOrDefault(false)
}

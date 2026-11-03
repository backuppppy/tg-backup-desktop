package org.tgbackup.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.system.exitProcess

/**
 * TDLib's JVM library copies its DLLs into a fresh directory under `java.io.tmpdir` on every start and
 * loads them from there. On Windows that is `%TEMP%`, which sits under the user name, so a Hebrew user
 * name gives a Hebrew path. [ensureAsciiTmpdir] makes sure the program runs with an ASCII temp dir
 * that belongs to TG Backup.
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

    /** Set in the restarted process so it never restarts again. */
    const val RELAUNCHED_ENV = "TGB_RELAUNCHED"

    /**
     * TDLib must find its DLLs on an ASCII path: from a Hebrew `java.io.tmpdir` the JVM fails with
     * `UnsatisfiedLinkError ... libcrypto-3-x64.dll: Can't find dependent libraries` (CI run 41725414413).
     * Java 17 fixes `java.io.tmpdir` when the JVM starts, so changing the property later has no effect.
     * Call this first in `main`: when the temp dir is not ASCII, it starts the same program again with
     * TEMP/TMP set to [choose]'s directory, waits for it and exits with its exit code.
     */
    fun ensureAsciiTmpdir(mainClass: String, args: Array<String>) {
        val os = System.getProperty("os.name")
        val tmpdir = System.getProperty("java.io.tmpdir")
        if (!needsRelaunch(tmpdir, os, System.getenv())) {
            if (os.startsWith("Windows", ignoreCase = true) && System.getenv(RELAUNCHED_ENV) == "1") {
                runCatching { cleanStale(Paths.get(tmpdir)) }
            }
            println("NATIVE_TMP $tmpdir")
            return
        }
        val dir = choose() ?: run {
            println("NATIVE_TMP no ASCII dir found, keeping $tmpdir")
            return
        }
        val info = ProcessHandle.current().info()
        val command = info.command().orElse(Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString())
        val cmd = relaunchCommand(
            command = command,
            jvmArgs = java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments,
            classPath = System.getProperty("java.class.path"),
            mainClass = mainClass,
            args = args.toList(),
        )
        println("NATIVE_TMP_RELAUNCH $dir")
        System.out.flush()
        val pb = ProcessBuilder(cmd).inheritIO()
        pb.environment()["TEMP"] = dir.toString()
        pb.environment()["TMP"] = dir.toString()
        pb.environment()[RELAUNCHED_ENV] = "1"
        exitProcess(pb.start().waitFor())
    }

    fun needsRelaunch(tmpdir: String, os: String, env: Map<String, String>): Boolean =
        os.startsWith("Windows", ignoreCase = true) && !isAscii(tmpdir) && env[RELAUNCHED_ENV] != "1"

    /**
     * The installed `TGBackup.exe` already carries its JVM options, so it gets only the program
     * arguments. A plain `java`/`javaw` (development, Gradle) needs the JVM options, classpath and main class.
     */
    fun relaunchCommand(
        command: String,
        jvmArgs: List<String>,
        classPath: String,
        mainClass: String,
        args: List<String>,
    ): List<String> {
        val exe = command.substringAfterLast('\\').substringAfterLast('/').lowercase()
        return if (exe == "java.exe" || exe == "javaw.exe" || exe == "java") {
            listOf(command) + jvmArgs + listOf("-cp", classPath, mainClass) + args
        } else {
            listOf(command) + args
        }
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

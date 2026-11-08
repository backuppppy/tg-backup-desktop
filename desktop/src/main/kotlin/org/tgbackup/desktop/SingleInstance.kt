package org.tgbackup.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tgbackup.desktop.server.INSTANCE_HEADER
import org.tgbackup.desktop.server.INTERNAL_SHOW_PATH
import org.tgbackup.io.AtomicFiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

/**
 * What a second launch got: the running instance showed its window, it never answered, or it ended meanwhile
 * and this launch now owns the data dir (it starts normally).
 */
enum class HandOff { Shown, NoAnswer, Acquired }

/** The running instance's address in `instance.json`. */
@Serializable
private data class InstanceInfo(val port: Int, val token: String)

/**
 * One program per data dir: the owner holds `instance.lock` (an OS file lock, released by the OS when the
 * process ends, also on a crash) and publishes its server's port and instance token in `instance.json`. A second
 * launch fails [acquire] and asks the owner to show its window ([handOff]). An `instance.json` left by a dead
 * owner never blocks: the lock decides, and the next owner removes the old file. [clock] (ms) and [sleep] pace
 * the hand-off's retries (tests pass fakes).
 */
class SingleInstance(
    private val dataDir: Path,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val lockFile = dataDir.resolve(LOCK_NAME)
    private val infoFile = dataDir.resolve(INFO_NAME)
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /** True when this process now owns the data dir; false when another program (or instance) holds it. */
    @Synchronized
    fun acquire(): Boolean {
        check(lock == null) { "already acquired" }
        Files.createDirectories(dataDir)
        val ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val got = try {
            ch.tryLock()
        } catch (e: OverlappingFileLockException) {
            // Held by another SingleInstance in this JVM.
            null
        }
        if (got == null) {
            ch.close()
            return false
        }
        channel = ch
        lock = got
        // A dead owner's address: a second launch must not knock on a port that may now be someone else's.
        Files.deleteIfExists(infoFile)
        return true
    }

    /** The owner, once its server is up: where a second launch finds it. */
    @Synchronized
    fun publish(port: Int, token: String) {
        check(lock != null) { "not acquired" }
        val tmp = dataDir.resolve("$INFO_NAME.tmp").toFile()
        tmp.writeText(json.encodeToString(InstanceInfo.serializer(), InstanceInfo(port, token)))
        AtomicFiles.replace(tmp, infoFile.toFile())
    }

    /**
     * The owner, when shutdown begins (before its server stops): a launch from now on finds no address and keeps
     * retrying until the lock is free, instead of knocking on a port that is about to close. The lock is kept.
     */
    @Synchronized
    fun unpublish() {
        runCatching { Files.deleteIfExists(infoFile) }
    }

    /**
     * A second launch: every [RETRY_MS] until [HANDOFF_DEADLINE_MS] have passed, takes the lock if the owner has
     * ended ([HandOff.Acquired], this instance now owns the data dir), else asks the owner to show its window
     * ([HandOff.Shown] on a 200, 2 s per attempt). No `instance.json` means not published yet (still starting, or
     * shutting down): retried like a failed attempt. At the deadline: [HandOff.NoAnswer].
     */
    fun handOff(): HandOff {
        val deadline = clock() + HANDOFF_DEADLINE_MS
        while (true) {
            if (acquire()) return HandOff.Acquired
            if (askToShow()) return HandOff.Shown
            if (clock() >= deadline) return HandOff.NoAnswer
            sleep(RETRY_MS)
        }
    }

    /** One attempt: true when the published owner answered `POST /internal/show` with a 200. */
    private fun askToShow(): Boolean {
        val info = runCatching { json.decodeFromString(InstanceInfo.serializer(), Files.readString(infoFile)) }
            .getOrNull() ?: return false
        return runCatching {
            val http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${info.port}$INTERNAL_SHOW_PATH"))
                .header(INSTANCE_HEADER, info.token)
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
            http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
        }.getOrDefault(false)
    }

    /** Gives the data dir up (the OS does the same when the process ends). */
    @Synchronized
    fun release() {
        val held = lock ?: return
        runCatching { Files.deleteIfExists(infoFile) }
        runCatching { held.release() }
        runCatching { channel?.close() }
        lock = null
        channel = null
    }

    companion object {
        const val LOCK_NAME = "instance.lock"
        const val INFO_NAME = "instance.json"
        const val RETRY_MS = 500L
        const val HANDOFF_DEADLINE_MS = 10_000L
        private val TIMEOUT: Duration = Duration.ofSeconds(2)
        private val json = Json { ignoreUnknownKeys = true }
    }
}

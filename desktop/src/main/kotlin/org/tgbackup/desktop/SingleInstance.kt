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

/** What a second launch got from the running instance. */
enum class HandOff { Shown, NoAnswer }

/** The running instance's address in `instance.json`. */
@Serializable
private data class InstanceInfo(val port: Int, val token: String)

/**
 * One program per data dir: the owner holds `instance.lock` (an OS file lock, released by the OS when the
 * process ends, also on a crash) and publishes its server's port and instance token in `instance.json`. A second
 * launch fails [acquire] and asks the owner to show its window ([handOff]). An `instance.json` left by a dead
 * owner never blocks: the lock decides, and the next owner removes the old file.
 */
class SingleInstance(private val dataDir: Path) {
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

    /** A second launch: asks the owner to show its window. Anything but a 200 within 2 s is [HandOff.NoAnswer]. */
    fun handOff(): HandOff {
        val info = runCatching { json.decodeFromString(InstanceInfo.serializer(), Files.readString(infoFile)) }
            .getOrNull() ?: return HandOff.NoAnswer
        return runCatching {
            val http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${info.port}$INTERNAL_SHOW_PATH"))
                .header(INSTANCE_HEADER, info.token)
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
            if (http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) HandOff.Shown else HandOff.NoAnswer
        }.getOrDefault(HandOff.NoAnswer)
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
        private val TIMEOUT: Duration = Duration.ofSeconds(2)
        private val json = Json { ignoreUnknownKeys = true }
    }
}

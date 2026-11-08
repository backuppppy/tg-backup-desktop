package org.tgbackup.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Holds the lock of the data dir in args[0] (its UTF-8 bytes in hex: Windows passes `main` arguments through the
 * ANSI code page, which can garble a Hebrew path) in its own JVM: prints `HELD`, then waits for a line on stdin.
 */
object SingleInstanceHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        val path = String(args[0].chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.UTF_8)
        val instance = SingleInstance(Path.of(path))
        println(if (instance.acquire()) "HELD" else "NOT_HELD")
        System.out.flush()
        readLine()
    }
}

class SingleInstanceTest {
    /** A Hebrew dir with a space, like the real data dir under a Hebrew user name. */
    private val dir: Path = Files.createTempDirectory("single-instance").resolve("תיקייה עם רווח")
    private val opened = mutableListOf<SingleInstance>()

    private fun instance() = SingleInstance(dir).also { opened += it }

    /** Fake time for the hand-off's retries: each sleep advances [now] and then runs [onSleep] (its count). */
    private var now = 0L
    private var sleeps = 0
    private var onSleep: (Int) -> Unit = {}

    private fun launch() = SingleInstance(dir, clock = { now }, sleep = { ms ->
        now += ms
        sleeps++
        onSleep(sleeps)
    }).also { opened += it }

    /** A local stand-in for the owner's server: 200 for POST /internal/show with [token], else 403. */
    private fun showServer(token: String, calls: AtomicInteger): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/internal/show") { ex ->
                val ok = ex.requestMethod == "POST" && ex.requestHeaders.getFirst("X-TGB-Instance") == token
                if (ok) calls.incrementAndGet()
                ex.sendResponseHeaders(if (ok) 200 else 403, -1)
                ex.close()
            }
            start()
        }

    @AfterTest
    fun releaseAll() = opened.forEach { it.release() }

    @Test
    fun theFirstAcquiresAndASecondInTheSameProcessDoesNot() {
        assertTrue(instance().acquire())
        assertFalse(instance().acquire())
        assertTrue(dir.resolve("instance.lock").exists())
    }

    @Test
    fun releaseLetsTheNextOneAcquire() {
        val first = instance()
        assertTrue(first.acquire())
        first.release()
        assertTrue(instance().acquire())
    }

    @Test
    fun anotherProcessHoldingTheLockBlocksAndItsEndFreesIt() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val hexDir = dir.toString().toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        // stdout only: the JVM may print lines of its own (such as "Picked up JAVA_TOOL_OPTIONS") on stderr.
        val stderr = Files.createTempFile("holder", ".err")
        val child = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), SingleInstanceHolder::class.java.name, hexDir)
            .redirectError(stderr.toFile()).start()
        try {
            val lines = Collections.synchronizedList(mutableListOf<String>())
            val reader = thread(isDaemon = true) {
                child.inputStream.bufferedReader().useLines { seq -> seq.takeWhile { lines.add(it); it != "HELD" && it != "NOT_HELD" }.count() }
            }
            reader.join(30_000)
            assertTrue("HELD" in lines, "child stdout: $lines; stderr: ${runCatching { stderr.readText() }.getOrDefault("")}")
            assertFalse(instance().acquire())
        } finally {
            child.destroyForcibly()
            child.waitFor(10, TimeUnit.SECONDS)
        }
        // The OS released the dead process's lock (on Windows possibly a moment after the process is gone).
        var acquired = false
        for (attempt in 1..20) {
            acquired = instance().acquire()
            if (acquired) break
            Thread.sleep(250)
        }
        assertTrue(acquired)
    }

    @Test
    fun aStaleInstanceJsonDoesNotBlockAndIsRemovedOnAcquire() {
        Files.createDirectories(dir)
        dir.resolve("instance.json").writeText("""{"port":1,"token":"old"}""")
        val owner = instance()
        assertTrue(owner.acquire())
        assertFalse(dir.resolve("instance.json").exists(), "the dead owner's address must not reach a second launch")
        owner.publish(4242, "new")
        assertTrue(dir.resolve("instance.json").readText().contains("4242"))
    }

    @Test
    fun handOffReachesTheRunningInstanceWithItsToken() {
        val calls = AtomicInteger()
        val server = showServer("tok", calls)
        try {
            val owner = instance()
            assertTrue(owner.acquire())
            owner.publish(server.address.port, "tok")
            assertEquals(HandOff.Shown, launch().handOff())
            assertEquals(1, calls.get())
            assertEquals(0, sleeps, "answered at the first attempt")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aWrongTokenRetriesUntilTheDeadline() {
        val calls = AtomicInteger()
        val server = showServer("tok", calls)
        try {
            val owner = instance()
            assertTrue(owner.acquire())
            owner.publish(server.address.port, "wrong")
            assertEquals(HandOff.NoAnswer, launch().handOff())
            assertEquals(0, calls.get())
            assertTrue(now >= SingleInstance.HANDOFF_DEADLINE_MS, "gave up at $now ms")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun anOwnerThatPublishesLateIsStillReached() {
        val calls = AtomicInteger()
        val server = showServer("tok", calls)
        try {
            val owner = instance()
            assertTrue(owner.acquire())
            // Not published yet (the engine is still starting): the launch retries until the address appears.
            onSleep = { n -> if (n == 4) owner.publish(server.address.port, "tok") }
            assertEquals(HandOff.Shown, launch().handOff())
            assertEquals(1, calls.get())
            assertEquals(4, sleeps)
            assertEquals(4 * SingleInstance.RETRY_MS, now)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun anOwnerThatNeverPublishesIsNoAnswerAtTheDeadline() {
        assertTrue(instance().acquire())
        assertEquals(HandOff.NoAnswer, launch().handOff())
        assertTrue(now >= SingleInstance.HANDOFF_DEADLINE_MS, "gave up at $now ms")
        assertTrue(now < SingleInstance.HANDOFF_DEADLINE_MS + SingleInstance.RETRY_MS, "kept going until $now ms")
        assertEquals((SingleInstance.HANDOFF_DEADLINE_MS / SingleInstance.RETRY_MS).toInt(), sleeps)
    }

    @Test
    fun anOwnerThatEndsDuringTheRetriesLeavesTheLockToTheLaunch() {
        val owner = instance()
        assertTrue(owner.acquire())
        onSleep = { n -> if (n == 3) owner.release() }
        val second = launch()
        assertEquals(HandOff.Acquired, second.handOff())
        assertFalse(instance().acquire(), "the launch now holds the lock")
        second.publish(4242, "mine")
        assertTrue(dir.resolve("instance.json").readText().contains("4242"))
    }

    @Test
    fun unpublishHidesTheAddressDuringShutdownButKeepsTheLock() {
        val owner = instance()
        assertTrue(owner.acquire())
        owner.publish(4242, "tok")
        owner.unpublish()
        assertFalse(dir.resolve("instance.json").exists())
        assertEquals(HandOff.NoAnswer, launch().handOff(), "still held, nothing to knock on")
    }

    @Test
    fun handOffWithoutARunningServerIsNoAnswer() {
        val owner = instance()
        assertTrue(owner.acquire())
        val closedPort = ServerSocket(0).use { it.localPort }
        owner.publish(closedPort, "tok")
        assertEquals(HandOff.NoAnswer, launch().handOff(), "nothing listens on the port")
        now = 0
        dir.resolve("instance.json").writeText("not json")
        assertEquals(HandOff.NoAnswer, launch().handOff(), "unreadable instance.json")
    }
}

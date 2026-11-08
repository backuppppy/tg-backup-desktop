package org.tgbackup.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Holds the lock of the data dir in args[0] in its own JVM: prints `HELD`, then waits for a line on stdin. */
object SingleInstanceHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        val instance = SingleInstance(Path.of(args[0]))
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
        val child = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), SingleInstanceHolder::class.java.name, dir.toString())
            .redirectErrorStream(true).start()
        try {
            val first = child.inputStream.bufferedReader().readLine()
            assertEquals("HELD", first)
            assertFalse(instance().acquire())
        } finally {
            child.destroyForcibly()
            child.waitFor(10, TimeUnit.SECONDS)
        }
        // The OS released the dead process's lock.
        assertTrue(instance().acquire())
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
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/show") { ex ->
            val ok = ex.requestMethod == "POST" && ex.requestHeaders.getFirst("X-TGB-Instance") == "tok"
            if (ok) calls.incrementAndGet()
            ex.sendResponseHeaders(if (ok) 200 else 403, -1)
            ex.close()
        }
        server.start()
        try {
            val owner = instance()
            assertTrue(owner.acquire())
            owner.publish(server.address.port, "tok")
            assertEquals(HandOff.Shown, instance().handOff())
            assertEquals(1, calls.get())
            owner.publish(server.address.port, "wrong")
            assertEquals(HandOff.NoAnswer, instance().handOff())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun handOffWithoutARunningServerIsNoAnswer() {
        assertEquals(HandOff.NoAnswer, instance().handOff(), "no instance.json")
        val owner = instance()
        assertTrue(owner.acquire())
        val closedPort = ServerSocket(0).use { it.localPort }
        owner.publish(closedPort, "tok")
        assertEquals(HandOff.NoAnswer, instance().handOff(), "nothing listens on the port")
        dir.resolve("instance.json").writeText("not json")
        assertEquals(HandOff.NoAnswer, instance().handOff(), "unreadable instance.json")
    }
}

package org.tgbackup.desktop.remote

import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteWatchdogTest {
    private fun dog(results: ArrayDeque<String?>, restarts: MutableList<String>) =
        RemoteWatchdog(probe = { results.removeFirst() }, restart = { restarts += "restart" }, report = { restarts += "report:$it" })

    @Test
    fun oneFailureDoesNotRestart() = runBlocking {
        val log = mutableListOf<String>()
        val d = dog(ArrayDeque(listOf("refused", null)), log)
        assertFalse(d.check())
        assertFalse(d.check())
        assertEquals(emptyList(), log)
    }

    @Test
    fun twoFailuresInARowRestartOnce() = runBlocking {
        val log = mutableListOf<String>()
        val d = dog(ArrayDeque(listOf("refused", "timeout", "refused")), log)
        assertFalse(d.check())
        assertTrue(d.check())
        assertEquals(listOf("report:timeout", "restart"), log)
        // The count starts over after a restart.
        assertFalse(d.check())
    }

    @Test
    fun aSuccessClearsTheCount() = runBlocking {
        val log = mutableListOf<String>()
        val d = dog(ArrayDeque(listOf("refused", null, "refused", null)), log)
        repeat(4) { assertFalse(d.check()) }
        assertEquals(emptyList(), log)
    }

    @Test
    fun aThrowingProbeCountsAsAFailure() = runBlocking {
        val log = mutableListOf<String>()
        val d = RemoteWatchdog(probe = { error("boom") }, restart = { log += "restart" }, report = { log += "report:$it" })
        assertFalse(d.check())
        assertTrue(d.check())
        assertEquals(listOf("report:boom", "restart"), log)
    }

    @Test
    fun httpProbeAcceptsAnyStatusLineAndReportsRefused() {
        ServerSocket(0).use { server ->
            thread {
                server.accept().use { c ->
                    c.getInputStream().read(ByteArray(256))
                    c.getOutputStream().write("HTTP/1.1 421 Misdirected\r\n\r\n".toByteArray())
                }
            }
            assertNull(RemoteWatchdog.httpProbe(server.localPort))
        }
        val free = ServerSocket(0).use { it.localPort }
        assertEquals("refused", RemoteWatchdog.httpProbe(free, 1_000))
    }

    @Test
    fun outOfPortsIsToldOncePerEpisode() {
        val n = OutOfPortsNotice()
        assertFalse(n.onFailure("refused"))
        assertTrue(n.onFailure("BindException: Address already in use: getsockopt"))
        assertFalse(n.onFailure("BindException: Address already in use: getsockopt"))
        assertTrue(RemoteWatchdog.isOutOfPorts("SocketException: No buffer space available (maximum connections reached?): connect"))
        n.onHealthy()
        assertTrue(n.onFailure("BindException: Address already in use: getsockopt"))
    }

    @Test
    fun anAnsweredProbeIsReported() = runBlocking {
        var healthy = 0
        val d = RemoteWatchdog(probe = { null }, restart = {}, report = {}, onHealthy = { healthy++ })
        d.check()
        assertEquals(1, healthy)
    }
}

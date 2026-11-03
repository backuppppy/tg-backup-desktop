package org.tgbackup.desktop.spike

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalPageServerTest {
    private val servers = mutableListOf<LocalPageServer>()
    private val http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()

    @AfterTest
    fun stopAll() = servers.forEach { it.stop() }

    private fun started(): Pair<LocalPageServer, Int> {
        val server = LocalPageServer()
        servers += server
        return server to server.start()
    }

    private fun get(port: Int, path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )

    @Test
    fun startReturnsABoundPort() {
        val (_, port) = started()
        assertTrue(port > 0, "port was $port")
    }

    @Test
    fun healthReturnsOk() {
        val (_, port) = started()
        val response = get(port, "/health")
        assertEquals(200, response.statusCode())
        assertEquals("ok", response.body())
    }

    @Test
    fun rootServesHebrewRtlPage() {
        val (_, port) = started()
        val response = get(port, "/")
        assertEquals(200, response.statusCode())
        val body = response.body()
        assertTrue(body.contains("dir=\"rtl\""), body)
        assertTrue(body.contains("שלום"), body)
        assertTrue(body.contains("<title>TG Backup</title>"), body)
    }

    @Test
    fun twoServersGetDifferentPorts() {
        val (_, a) = started()
        val (_, b) = started()
        assertNotEquals(a, b)
    }
}

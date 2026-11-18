package org.tgbackup.desktop.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Watches the phone server while phone access runs: [probe] every [everyMs]; after [failuresToRestart] failures in a
 * row it [report]s and [restart]s the server. A success clears the count. On 17/11 the server's port stayed open but
 * refused every connection until phone access was switched off and on.
 */
class RemoteWatchdog(
    private val probe: suspend () -> String?,
    private val restart: suspend () -> Unit,
    private val report: (failure: String) -> Unit,
    private val everyMs: Long = 30_000,
    private val failuresToRestart: Int = 2,
    /** Told of each answered probe. */
    private val onHealthy: () -> Unit = {},
) {
    private var failures = 0

    /** One look: [probe] answers null when the server answered, else what went wrong. True when it restarted. */
    suspend fun check(): Boolean {
        val failure = try {
            probe()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e::class.java.simpleName
        }
        if (failure == null) {
            failures = 0
            onHealthy()
            return false
        }
        if (++failures < failuresToRestart) return false
        failures = 0
        report(failure)
        restart()
        return true
    }

    /** Checks until cancelled; the first check comes after [everyMs]. */
    suspend fun run() {
        failures = 0
        while (true) {
            delay(everyMs)
            check()
        }
    }

    companion object {
        /**
         * The probe could not even open a connection because Windows has no free local port (17/11: 20,537 TIME_WAIT
         * connections that Windows never purged). Restarting the server cannot help; restarting the PC does.
         */
        fun isOutOfPorts(failure: String): Boolean =
            failure.startsWith("BindException") ||
                failure.contains("No buffer space available") ||
                failure.contains("Cannot assign requested address")

        /**
         * The real probe: a request to the server on 127.0.0.1:[port]. Any HTTP status line counts as alive (the
         * Host check may answer 4xx: the server still accepts). Null when alive, else the failure.
         */
        fun httpProbe(port: Int, timeoutMs: Int = 5_000): String? = try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                s.soTimeout = timeoutMs
                s.getOutputStream().apply {
                    write("GET /api/health HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                val line = InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1).buffered().readLine()
                if (line != null && line.startsWith("HTTP/")) null else "no HTTP answer (${line?.take(40) ?: "closed"})"
            }
        } catch (e: java.net.SocketTimeoutException) {
            "timeout"
        } catch (e: java.net.ConnectException) {
            "refused"
        } catch (e: java.io.IOException) {
            e::class.java.simpleName + ": " + (e.message ?: "")
        }
    }
}

/**
 * Says "Windows ran out of ports" once per episode: the first out-of-ports failure is told, the next ones are not, until
 * the server answers again.
 */
class OutOfPortsNotice {
    private var told = false

    /** True when this failure should be told now. */
    @Synchronized
    fun onFailure(failure: String): Boolean {
        if (!RemoteWatchdog.isOutOfPorts(failure) || told) return false
        told = true
        return true
    }

    @Synchronized
    fun onHealthy() {
        told = false
    }
}

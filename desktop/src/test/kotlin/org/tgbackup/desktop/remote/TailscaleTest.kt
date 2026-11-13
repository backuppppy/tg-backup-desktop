package org.tgbackup.desktop.remote

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The fixtures are synthetic (built from the documented JSON of tailscale 1.104.5), not captured from a tailnet. */
class TailscaleTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/tailscale/$name")) { "fixture $name" }.readText()

    @Test
    fun notRunningIsNotConnected() {
        assertEquals(TsStatus.NotConnected, parseStatus(fixture("status-stopped.json")))
        assertEquals(TsStatus.NotConnected, parseStatus("""{"BackendState":"NeedsLogin","Self":null}"""))
    }

    @Test
    fun runningWithoutCertsHasHttpsOff() {
        assertEquals(TsStatus.Up("pc.tail1234.ts.net", httpsOn = false), parseStatus(fixture("status-running-nocerts.json")))
        assertEquals(
            TsStatus.Up("pc.tail1234.ts.net", httpsOn = false),
            parseStatus("""{"BackendState":"Running","Self":{"DNSName":"pc.tail1234.ts.net."},"CertDomains":[]}"""),
        )
    }

    @Test
    fun runningWithCertsHasHttpsOn() {
        assertEquals(TsStatus.Up("pc.tail1234.ts.net", httpsOn = true), parseStatus(fixture("status-running-certs.json")))
    }

    @Test
    fun theTrailingDotOfTheNameGoes() {
        val up = parseStatus("""{"BackendState":"Running","Self":{"DNSName":"my-pc.tail9.ts.net."},"CertDomains":["x"]}""")
        assertEquals("my-pc.tail9.ts.net", (up as TsStatus.Up).dnsName)
        val noDot = parseStatus("""{"BackendState":"Running","Self":{"DNSName":"my-pc.tail9.ts.net"},"CertDomains":["x"]}""")
        assertEquals("my-pc.tail9.ts.net", (noDot as TsStatus.Up).dnsName)
    }

    @Test
    fun brokenJsonIsFailed() {
        assertTrue(parseStatus("not json") is TsStatus.Failed)
        assertTrue(parseStatus("""{"BackendState":"Running","Self":{"DNSName":""}}""") is TsStatus.Failed)
    }

    @Test
    fun serveStatusOursBusyNone() {
        assertEquals(ServeConfig.Ours, parseServeStatus(fixture("serve-ours.json")))
        assertEquals(ServeConfig.Busy, parseServeStatus(fixture("serve-busy.json")))
        assertEquals(ServeConfig.None, parseServeStatus(fixture("serve-none.json")))
        assertEquals(ServeConfig.None, parseServeStatus("null"))
        assertEquals(ServeConfig.None, parseServeStatus(""))
        // Another port is someone else's, and not in our way.
        assertEquals(ServeConfig.None, parseServeStatus(fixture("serve-other-port.json")))
    }

    @Test
    fun ourEntryWithAnythingElseOn443IsBusy() {
        // A second path under 443.
        assertEquals(
            ServeConfig.Busy,
            parseServeStatus(
                """{"TCP":{"443":{"HTTPS":true}},"Web":{"pc.ts.net:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:47800"},"/x":{"Path":"C:\\x"}}}}}""",
            ),
        )
        // A raw TCP forward on 443.
        assertEquals(ServeConfig.Busy, parseServeStatus("""{"TCP":{"443":{"TCPForward":"127.0.0.1:22"}}}"""))
        // Funnel on our entry: not ours (the program never opens it to the internet).
        assertEquals(
            ServeConfig.Busy,
            parseServeStatus(
                """{"TCP":{"443":{"HTTPS":true}},"Web":{"pc.ts.net:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:47800"}}}},"AllowFunnel":{"pc.ts.net:443":true}}""",
            ),
        )
        // A foreground `tailscale serve` session on 443.
        assertEquals(
            ServeConfig.Busy,
            parseServeStatus("""{"Foreground":{"s1":{"TCP":{"443":{"HTTPS":true}},"Web":{"pc.ts.net:443":{"Handlers":{"/":{"Text":"hi"}}}}}}}"""),
        )
    }

    @Test
    fun withoutAnExeNothingRuns() {
        val cli = ProcessTailscale(null)
        assertEquals(TsStatus.NotInstalled, cli.status())
        val missing = ProcessTailscale(File(Files.createTempDirectory("ts").toFile(), "tailscale-missing.exe"))
        assertEquals(TsStatus.NotInstalled, missing.status())
    }

    /** A fake `tailscale` that hangs for a minute. */
    private fun hangingExe(): File {
        val dir = Files.createTempDirectory("ts-hang").toFile()
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        return if (windows) {
            File(dir, "tailscale.cmd").apply { writeText("@echo off\r\nping -n 60 127.0.0.1 >nul\r\n") }
        } else {
            File(dir, "tailscale").apply {
                writeText("#!/bin/sh\nsleep 60\n")
                setExecutable(true)
            }
        }
    }

    @Test
    fun aHangingCliIsKilledAtTheTimeout() {
        val started = System.nanoTime()
        val status = ProcessTailscale(hangingExe(), timeoutMs = 500).status()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(TsStatus.Failed("timeout"), status)
        assertTrue(tookMs < 2_000, "took $tookMs ms")
    }

    @Test
    fun theTimeoutIsTwentySeconds() {
        assertEquals(20_000L, ProcessTailscale(null).timeoutMs)
    }

    @Test
    fun anInterruptedCallKillsTheCliAndEnds() {
        val cli = ProcessTailscale(hangingExe())
        var thrown: Throwable? = null
        val t = Thread { thrown = runCatching { cli.status() }.exceptionOrNull() }
        t.start()
        Thread.sleep(500)
        val started = System.nanoTime()
        t.interrupt()
        t.join(2_000)
        assertFalse(t.isAlive, "still running after ${(System.nanoTime() - started) / 1_000_000} ms")
        assertTrue(thrown is InterruptedException, "$thrown")
    }

    @Test
    fun anEntryForAnotherNameIsNotOurs() {
        val old = fixture("serve-old-name.json")
        // The PC's tailnet name changed: our entry under the old name serves nothing at the new url.
        assertEquals(ServeConfig.None, parseServeStatus(old, "pc.tail1234.ts.net"))
        assertEquals(ServeConfig.Ours, parseServeStatus(old, "old-pc.tail1234.ts.net"))
        // No name (switching off): any name of ours is ours.
        assertEquals(ServeConfig.Ours, parseServeStatus(old, null))
        assertEquals(ServeConfig.Ours, parseServeStatus(fixture("serve-ours.json"), "pc.tail1234.ts.net"))
        // Both names, ours: ours.
        assertEquals(
            ServeConfig.Ours,
            parseServeStatus(
                """{"TCP":{"443":{"HTTPS":true}},"Web":{"old-pc.tail1234.ts.net:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:47800"}}},""" +
                    """"pc.tail1234.ts.net:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:47800"}}}}}""",
                "pc.tail1234.ts.net",
            ),
        )
        // Someone else's under the new name stays Busy.
        assertEquals(ServeConfig.Busy, parseServeStatus(fixture("serve-busy.json"), "pc.tail1234.ts.net"))
    }

    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    /** A fake `tailscale` that appends its arguments to `args.txt` and prints [output]. */
    private fun recordingExe(output: String, exitCode: Int = 0): Pair<File, File> {
        val dir = Files.createTempDirectory("ts-args").toFile()
        val args = File(dir, "args.txt")
        File(dir, "out.txt").writeText(output)
        val exe = if (windows) {
            File(dir, "tailscale.cmd").apply {
                writeText("@echo off\r\necho %*>>\"${args.path}\"\r\ntype \"${dir.path}\\out.txt\"\r\nexit /b $exitCode\r\n")
            }
        } else {
            File(dir, "tailscale").apply {
                writeText("#!/bin/sh\necho \"$*\" >> '${args.path}'\ncat '${dir.path}/out.txt'\nexit $exitCode\n")
                setExecutable(true)
            }
        }
        return exe to args
    }

    @Test
    fun theCliGetsTheDocumentedArguments() {
        val (exe, args) = recordingExe(fixture("serve-ours.json"))
        val cli = ProcessTailscale(exe)
        assertEquals(ServeConfig.Ours, cli.serveStatus("pc.tail1234.ts.net"))
        cli.serveOn(47800)
        cli.serveOff()
        assertEquals(
            listOf("serve status --json", "serve --bg --yes --https=443 http://127.0.0.1:47800", "serve --yes --https=443 off"),
            args.readLines().map { it.trim() },
        )
        val (statusExe, statusArgs) = recordingExe(fixture("status-running-certs.json"))
        assertEquals(TsStatus.Up("pc.tail1234.ts.net", httpsOn = true), ProcessTailscale(statusExe).status())
        assertEquals(listOf("status --json"), statusArgs.readLines().map { it.trim() })
    }

    @Test
    fun aFailingServeCallThrowsItsMessage() {
        val (exe, _) = recordingExe("error: serve config denied\n", exitCode = 1)
        val e = assertFailsWith<TsError> { ProcessTailscale(exe).serveOn(47800) }
        assertEquals("error: serve config denied", e.detail)
    }

    @Test
    fun aStoppedServiceIsNotConnected() {
        val (exe, _) = recordingExe("failed to connect to local tailscaled; it doesn't appear to be running\n", exitCode = 1)
        assertEquals(TsStatus.NotConnected, ProcessTailscale(exe).status())
        val (other, _) = recordingExe("something else broke\n", exitCode = 1)
        assertEquals(TsStatus.Failed("something else broke"), ProcessTailscale(other).status())
    }

    @Test
    fun findTailscaleLooksOnPathThenTheInstalledPlace() {
        val dir = Files.createTempDirectory("ts-path").toFile()
        val onPath = File(dir, "tailscale.exe").apply { writeText("") }
        val installed = File(Files.createTempDirectory("ts-inst").toFile(), "tailscale.exe").apply { writeText("") }
        val empty = Files.createTempDirectory("ts-empty").toFile()
        val sep = File.pathSeparator
        assertEquals(onPath, findTailscale("${empty.path}$sep${dir.path}", windows = true, installed = installed))
        assertEquals(installed, findTailscale(empty.path, windows = true, installed = installed))
        assertNull(findTailscale(empty.path, windows = true, installed = File(empty, "none.exe")))
        assertNull(findTailscale(dir.path, windows = false, installed = installed))
    }
}

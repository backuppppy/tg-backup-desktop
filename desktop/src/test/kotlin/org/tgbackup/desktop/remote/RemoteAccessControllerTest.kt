package org.tgbackup.desktop.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tgbackup.app.engine.LogBus
import org.tgbackup.desktop.DesktopPrefs
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val NAME = "pc.tail1234.ts.net"

class RemoteAccessControllerTest {
    private val dir = Files.createTempDirectory("remote-ctl").toFile()
    private val prefs = DesktopPrefs(dir)
    private val auth = RemoteAuth(dir, System::currentTimeMillis) { _, _, _ -> ByteArray(32) }
    private val log = LogBus(dir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cli = FakeTailscale()

    private val started = AtomicInteger()
    private val stopped = AtomicInteger()
    @Volatile private var names: (() -> Set<String>)? = null

    /** Counts starts and stops; keeps the names the server would read. */
    private var startServer: (() -> Set<String>) -> AutoCloseable = { n ->
        started.incrementAndGet()
        names = n
        AutoCloseable { stopped.incrementAndGet() }
    }

    private val controller by lazy {
        RemoteAccessController(prefs, auth, cli, { startServer(it) }, log, scope, pollMs = 20, runningPollMs = 20).also { it.start() }
    }

    @AfterTest
    fun tearDown() {
        controller.close()
        scope.cancel()
    }

    private fun awaitState(state: String, url: String? = null) = runBlocking {
        withTimeout(5.seconds) { controller.status.first { it.state == state && (url == null || it.url == url) } }
    }

    /** A few more polls, for "never happens" checks. */
    private fun somePolls() = runBlocking { delay(200) }

    private fun logged(): List<String> = log.entries.value.map { it.msg }

    @Test
    fun followsTailscaleFromOffToRunning() {
        auth.setPassword("secret1")
        assertEquals("Off", controller.status.value.state)
        assertFalse(controller.status.value.enabled)

        assertTrue(controller.setEnabled(true))
        awaitState("NotInstalled")
        assertTrue(controller.status.value.enabled)
        cli.status = TsStatus.NotConnected
        awaitState("NotConnected")
        cli.status = TsStatus.Up(NAME, httpsOn = false)
        awaitState("HttpsOff")
        assertEquals(0, started.get())
        cli.status = TsStatus.Failed("boom")
        assertEquals("boom", awaitState("Error").detail)
        cli.status = TsStatus.Up(NAME, httpsOn = true)
        val running = awaitState("Running")
        assertEquals("https://$NAME", running.url)
        assertTrue(running.hasPassword)
        somePolls()
        assertEquals(1, cli.serveOnCalls.get())
        assertEquals(1, started.get())
        assertEquals(setOf(NAME), names!!())

        val lines = logged()
        assertTrue("גישה מהטלפון: Tailscale לא מותקן במחשב." in lines, "$lines")
        assertTrue("גישה מהטלפון: Tailscale מותקן אבל לא מחובר. פותחים את Tailscale ליד השעון ומתחברים." in lines, "$lines")
        assertTrue("גישה מהטלפון: צריך להפעיל https בחשבון Tailscale (שלב 2)." in lines, "$lines")
        assertTrue("גישה מהטלפון: שגיאה מ-Tailscale: boom" in lines, "$lines")
        assertEquals(1, lines.count { it == "הגישה מהטלפון פועלת: https://$NAME" }, "$lines")
    }

    @Test
    fun anotherServeOn443IsNeverTouched() {
        auth.setPassword("secret1")
        cli.status = TsStatus.Up(NAME, httpsOn = true)
        cli.serve = ServeConfig.Busy
        controller.setEnabled(true)
        awaitState("ServeBusy")
        somePolls()
        controller.setEnabled(false)
        awaitState("Off")
        somePolls()
        assertEquals(0, cli.serveOnCalls.get())
        assertEquals(0, cli.serveOffCalls.get())
        assertEquals(0, started.get())
        assertTrue("גישה מהטלפון: ב-Tailscale כבר מוגדר שירות אחר על https. התוכנה לא דורסת אותו." in logged())
    }

    @Test
    fun switchingOffRemovesOurEntryOnce() {
        auth.setPassword("secret1")
        cli.status = TsStatus.Up(NAME, httpsOn = true)
        controller.setEnabled(true)
        awaitState("Running")
        controller.setEnabled(false)
        val off = awaitState("Off")
        assertFalse(off.enabled)
        assertNull(off.url)
        somePolls()
        assertEquals(1, cli.serveOffCalls.get())
        assertEquals(1, stopped.get())
        assertTrue("הגישה מהטלפון כובתה" in logged())
    }

    @Test
    fun aTakenPortIsPortBusy() {
        auth.setPassword("secret1")
        val taken = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        startServer = { ServerSocket().use { it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), taken.localPort)) }; AutoCloseable { } }
        taken.use {
            cli.status = TsStatus.Up(NAME, httpsOn = true)
            controller.setEnabled(true)
            awaitState("PortBusy")
            somePolls()
            assertEquals(0, cli.serveOnCalls.get())
            assertTrue("גישה מהטלפון: הפורט 47800 במחשב תפוס על ידי תוכנה אחרת." in logged())
        }
    }

    @Test
    fun aNewTailnetNameGivesANewUrl() {
        auth.setPassword("secret1")
        cli.status = TsStatus.Up(NAME, httpsOn = true)
        controller.setEnabled(true)
        awaitState("Running", "https://$NAME")
        cli.status = TsStatus.Up("laptop.tail1234.ts.net", httpsOn = true)
        awaitState("Running", "https://laptop.tail1234.ts.net")
        assertEquals(setOf("laptop.tail1234.ts.net"), names!!())
        assertEquals(1, started.get())
        assertTrue("הגישה מהטלפון פועלת: https://laptop.tail1234.ts.net" in logged())
    }

    @Test
    fun enablingNeedsAPasswordAndTheStatusFollowsAuth() {
        assertFalse(controller.status.value.hasPassword)
        assertFalse(controller.setEnabled(true))
        assertFalse(prefs.value.value.remoteAccess)
        controller.setPassword("secret1")
        runBlocking { withTimeout(5.seconds) { controller.status.first { it.hasPassword } } }
        assertTrue(controller.setEnabled(true))
        assertTrue(prefs.value.value.remoteAccess)
        controller.disconnectAll()
        assertTrue("כל המכשירים נותקו" in logged())
    }

    @Test
    fun startingWithTheSwitchOnConnectsWithoutAChange() {
        auth.setPassword("secret1")
        prefs.update { it.copy(remoteAccess = true) }
        cli.status = TsStatus.Up(NAME, httpsOn = true)
        cli.serve = ServeConfig.Ours
        awaitState("Running")
        somePolls()
        // Already ours from the last run: not set again.
        assertEquals(0, cli.serveOnCalls.get())
        assertEquals(1, started.get())
    }
}

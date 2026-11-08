package org.tgbackup.desktop

import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowLoopTest {
    /** Records what the loop did, in order: "window", "ask", "icon", "hide", "front". */
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private inner class FakeTray : TrayControl {
        @Volatile var onOpen: () -> Unit = {}
        @Volatile var onQuit: () -> Unit = {}
        val shown = AtomicBoolean(false)

        override fun showIcon(onOpen: () -> Unit, onQuit: () -> Unit) {
            this.onOpen = onOpen
            this.onQuit = onQuit
            shown.set(true)
            events += "icon"
        }

        override fun hideIcon() {
            shown.set(false)
            events += "hide"
        }
    }

    private val windows = AtomicInteger(0)
    private val window: () -> WindowOutcome = {
        windows.incrementAndGet()
        events += "window"
        WindowOutcome.CLOSED
    }

    private fun loop(
        busy: Boolean,
        answer: Boolean,
        tray: TrayControl?,
        startHidden: Boolean = false,
        window: () -> WindowOutcome = this.window,
        busyFlag: () -> Boolean = { busy },
        toFront: () -> Unit = { events += "front" },
    ) = WindowLoop(
        window = window,
        busy = busyFlag,
        askBackground = { events += "ask"; answer },
        tray = tray,
        startHidden = startHidden,
        toFront = toFront,
    )

    /** Runs the loop on its own thread (it blocks while it waits in the tray). */
    private fun start(loop: WindowLoop): CompletableFuture<WindowOutcome> {
        val result = CompletableFuture<WindowOutcome>()
        thread(isDaemon = true, name = "window-loop-test") {
            runCatching { loop.run() }.fold(result::complete, result::completeExceptionally)
        }
        return result
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what; events=$events" }
            Thread.sleep(5)
        }
    }

    @Test
    fun nothingRunningClosesOnceWithoutAsking() {
        val outcome = loop(busy = false, answer = true, tray = FakeTray()).run()
        assertEquals(WindowOutcome.CLOSED, outcome)
        assertEquals(listOf("window"), events.toList())
    }

    @Test
    fun busyAndNoClosesAndTheIconIsNeverShown() {
        val tray = FakeTray()
        val outcome = loop(busy = true, answer = false, tray = tray).run()
        assertEquals(WindowOutcome.CLOSED, outcome)
        assertEquals(listOf("window", "ask"), events.toList())
    }

    @Test
    fun busyAndYesShowsTheIconAndShowOpensTheWindowAgainWithoutTheIcon() {
        val tray = FakeTray()
        // The second window closes with nothing running: the program ends.
        val busy = AtomicBoolean(true)
        val l = loop(busy = true, answer = true, tray = tray, busyFlag = { busy.get() }, window = {
            windows.incrementAndGet()
            events += "window"
            if (windows.get() == 2) {
                assertFalse(tray.shown.get(), "the icon is gone while the window is open")
                busy.set(false)
            }
            WindowOutcome.CLOSED
        })
        val result = start(l)
        waitUntil("the icon") { tray.shown.get() }
        assertEquals(1, windows.get())
        l.show()
        assertEquals(WindowOutcome.CLOSED, result.get(5, TimeUnit.SECONDS))
        assertEquals(listOf("window", "ask", "icon", "hide", "window"), events.toList())
        assertFalse(tray.shown.get())
    }

    @Test
    fun theIconsOpenItemOpensTheWindowAgain() {
        val tray = FakeTray()
        val busy = AtomicBoolean(true)
        val l = loop(busy = true, answer = true, tray = tray, busyFlag = { busy.get() }, window = {
            if (windows.incrementAndGet() == 2) busy.set(false)
            events += "window"
            WindowOutcome.CLOSED
        })
        val result = start(l)
        waitUntil("the icon") { tray.shown.get() }
        tray.onOpen()
        assertEquals(WindowOutcome.CLOSED, result.get(5, TimeUnit.SECONDS))
        assertEquals(2, windows.get())
    }

    @Test
    fun busyAndYesThenQuitClosesAndHidesTheIcon() {
        val tray = FakeTray()
        val l = loop(busy = true, answer = true, tray = tray)
        val result = start(l)
        waitUntil("the icon") { tray.shown.get() }
        tray.onQuit()
        assertEquals(WindowOutcome.CLOSED, result.get(5, TimeUnit.SECONDS))
        assertEquals(listOf("window", "ask", "icon", "hide"), events.toList())
        assertEquals(1, windows.get())
    }

    @Test
    fun quitFromTheTrayNeverAsks() {
        val tray = FakeTray()
        val l = loop(busy = true, answer = true, tray = tray, startHidden = true)
        val result = start(l)
        waitUntil("the icon") { tray.shown.get() }
        l.quit()
        assertEquals(WindowOutcome.CLOSED, result.get(5, TimeUnit.SECONDS))
        assertEquals(listOf("icon", "hide"), events.toList())
    }

    @Test
    fun startHiddenOpensNoWindowUntilShow() {
        val tray = FakeTray()
        val l = loop(busy = false, answer = true, tray = tray, startHidden = true)
        val result = start(l)
        waitUntil("the icon") { tray.shown.get() }
        Thread.sleep(100)
        assertEquals(0, windows.get())
        l.show()
        assertEquals(WindowOutcome.CLOSED, result.get(5, TimeUnit.SECONDS))
        assertEquals(listOf("icon", "hide", "window"), events.toList())
    }

    @Test
    fun startHiddenWithoutATrayOpensTheWindow() {
        val outcome = loop(busy = false, answer = true, tray = null, startHidden = true).run()
        assertEquals(WindowOutcome.CLOSED, outcome)
        assertEquals(listOf("window"), events.toList())
    }

    @Test
    fun noTrayAndBusyClosesWithoutAsking() {
        val outcome = loop(busy = true, answer = true, tray = null).run()
        assertEquals(WindowOutcome.CLOSED, outcome)
        assertEquals(listOf("window"), events.toList())
    }

    @Test
    fun aMissingWebViewEndsAtOnce() {
        val outcome = loop(busy = true, answer = true, tray = FakeTray(), window = {
            events += "window"
            WindowOutcome.NO_WEBVIEW
        }).run()
        assertEquals(WindowOutcome.NO_WEBVIEW, outcome)
        assertEquals(listOf("window"), events.toList())
    }

    @Test
    fun showWhileTheWindowIsOpenBringsItToTheFront() {
        val inWindow = CompletableFuture<Unit>()
        val release = CompletableFuture<Unit>()
        val l = loop(busy = false, answer = true, tray = FakeTray(), window = {
            events += "window"
            inWindow.complete(Unit)
            release.get(5, TimeUnit.SECONDS)
            WindowOutcome.CLOSED
        })
        val result = start(l)
        inWindow.get(5, TimeUnit.SECONDS)
        l.show()
        release.complete(Unit)
        assertEquals(WindowOutcome.CLOSED, result.get(5, TimeUnit.SECONDS))
        assertEquals(listOf("window", "front"), events.toList())
    }

    @Test
    fun aShowDuringTheQuestionReopensTheWindowWithoutTheIcon() {
        val tray = FakeTray()
        val busy = AtomicBoolean(true)
        lateinit var l: WindowLoop
        l = WindowLoop(
            window = {
                if (windows.incrementAndGet() == 2) busy.set(false)
                events += "window"
                WindowOutcome.CLOSED
            },
            busy = { busy.get() },
            // A second launch arrives while the box is up; then the user says Yes.
            askBackground = { events += "ask"; l.show(); true },
            tray = tray,
            startHidden = false,
            toFront = { events += "front" },
        )
        assertEquals(WindowOutcome.CLOSED, l.run())
        assertEquals(listOf("window", "ask", "window"), events.toList())
    }

    @Test
    fun aShowAfterTheLoopEndedDoesNothing() {
        val l = loop(busy = false, answer = true, tray = FakeTray())
        assertEquals(WindowOutcome.CLOSED, l.run())
        l.show()
        l.quit()
        assertEquals(listOf("window"), events.toList())
        assertTrue(windows.get() == 1)
    }
}

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
        toFront: () -> Boolean = { events += "front"; true },
    ) = WindowLoop(
        window = window,
        busy = busyFlag,
        askBackground = { events += "ask"; answer },
        tray = tray,
        startHidden = startHidden,
        toFront = toFront,
        sleep = { events += "sleep $it" },
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
            toFront = { events += "front"; true },
        )
        assertEquals(WindowOutcome.CLOSED, l.run())
        assertEquals(listOf("window", "ask", "window"), events.toList())
    }

    @Test
    fun aShowDuringTheQuestionReopensTheWindowEvenOnNo() {
        val busy = AtomicBoolean(true)
        lateinit var l: WindowLoop
        l = WindowLoop(
            window = {
                if (windows.incrementAndGet() == 2) busy.set(false)
                events += "window"
                WindowOutcome.CLOSED
            },
            busy = { busy.get() },
            askBackground = { events += "ask"; l.show(); false },
            tray = FakeTray(),
            startHidden = false,
            toFront = { events += "front"; true },
        )
        assertEquals(WindowOutcome.CLOSED, l.run())
        assertEquals(listOf("window", "ask", "window"), events.toList())
    }

    @Test
    fun aShowRightAfterTheCloseWithNothingRunningReopensTheWindow() {
        lateinit var l: WindowLoop
        l = WindowLoop(
            window = {
                windows.incrementAndGet()
                events += "window"
                WindowOutcome.CLOSED
            },
            // The second launch arrives between the close and the busy check, only the first time.
            busy = { if (windows.get() == 1 && "shown" !in events) { events += "shown"; l.show() }; false },
            askBackground = { events += "ask"; true },
            tray = FakeTray(),
            startHidden = false,
            toFront = { events += "front"; true },
        )
        assertEquals(WindowOutcome.CLOSED, l.run())
        assertEquals(listOf("window", "shown", "window"), events.toList())
    }

    @Test
    fun anInterruptedWaitLeavesNoIcon() {
        val tray = FakeTray()
        val l = loop(busy = true, answer = true, tray = tray)
        val result = CompletableFuture<WindowOutcome>()
        val t = thread(isDaemon = true, name = "window-loop-test") {
            runCatching { l.run() }.fold(result::complete, result::completeExceptionally)
        }
        waitUntil("the icon") { tray.shown.get() }
        t.interrupt()
        t.join(5_000)
        assertTrue(result.isCompletedExceptionally)
        assertFalse(tray.shown.get())
        assertEquals(listOf("window", "ask", "icon", "hide"), events.toList())
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

    private fun icons(): Int = synchronized(events) { events.count { it == "icon" } }

    /** Busy and Yes, then the icon's "פתח": the windows after the first come from [outcomes] (null = it throws). */
    private fun reopenLoop(tray: FakeTray, busy: AtomicBoolean, vararg outcomes: WindowOutcome?): WindowLoop =
        loop(busy = true, answer = true, tray = tray, busyFlag = { busy.get() }, window = {
            val n = windows.incrementAndGet()
            events += "window"
            if (n == 1) return@loop WindowOutcome.CLOSED
            val o = outcomes.getOrElse(n - 2) { WindowOutcome.CLOSED }
            if (o == WindowOutcome.CLOSED) busy.set(false)
            o ?: throw IllegalStateException("window failed")
        })

    @Test
    fun aReopenThatFailsOnceIsTriedAgainAndOpens() {
        val tray = FakeTray()
        val l = reopenLoop(tray, AtomicBoolean(true), WindowOutcome.NO_WEBVIEW, WindowOutcome.CLOSED)
        val result = start(l)
        waitUntil("the icon") { tray.shown.get() }
        tray.onOpen()
        assertEquals(WindowOutcome.CLOSED, result.get(5, TimeUnit.SECONDS))
        assertEquals(
            listOf("window", "ask", "icon", "hide", "window", "sleep $REOPEN_RETRY_MS", "window"),
            events.toList(),
        )
    }

    @Test
    fun aReopenThatThrowsOnceIsTriedAgainAndOpens() {
        val tray = FakeTray()
        val l = reopenLoop(tray, AtomicBoolean(true), null, WindowOutcome.CLOSED)
        val result = start(l)
        waitUntil("the icon") { tray.shown.get() }
        l.show()
        assertEquals(WindowOutcome.CLOSED, result.get(5, TimeUnit.SECONDS))
        assertEquals(3, windows.get())
    }

    @Test
    fun aReopenThatFailsTwiceGoesBackToTheTrayAndQuitEnds() {
        val tray = FakeTray()
        val l = reopenLoop(tray, AtomicBoolean(true), WindowOutcome.NO_WEBVIEW, null)
        val result = start(l)
        waitUntil("the icon") { tray.shown.get() }
        tray.onOpen()
        // The icon comes back (shown a second time) and the program does not end.
        waitUntil("the icon again") { icons() == 2 && tray.shown.get() }
        Thread.sleep(100)
        assertFalse(result.isDone)
        tray.onQuit()
        assertEquals(WindowOutcome.CLOSED, result.get(5, TimeUnit.SECONDS))
        assertEquals(
            listOf("window", "ask", "icon", "hide", "window", "sleep $REOPEN_RETRY_MS", "window", "icon", "hide"),
            events.toList(),
        )
        assertFalse(tray.shown.get())
    }

    @Test
    fun afterTwoFailedReopensTheIconCanOpenTheWindowAgain() {
        val tray = FakeTray()
        val l = reopenLoop(tray, AtomicBoolean(true), WindowOutcome.NO_WEBVIEW, WindowOutcome.NO_WEBVIEW, WindowOutcome.CLOSED)
        val result = start(l)
        waitUntil("the icon") { tray.shown.get() }
        tray.onOpen()
        waitUntil("the icon again") { icons() == 2 && tray.shown.get() }
        tray.onOpen()
        assertEquals(WindowOutcome.CLOSED, result.get(5, TimeUnit.SECONDS))
        assertEquals(4, windows.get())
    }

    @Test
    fun aFirstWindowThatThrowsStillEndsTheLoop() {
        val l = loop(busy = true, answer = true, tray = FakeTray(), window = { error("no window") })
        val result = start(l)
        assertTrue(runCatching { result.get(5, TimeUnit.SECONDS) }.isFailure)
        assertTrue(synchronized(events) { events.none { it.startsWith("sleep") } })
    }

    @Test
    fun aFailedReopenWithoutATrayEnds() {
        lateinit var l: WindowLoop
        l = WindowLoop(
            window = {
                val n = windows.incrementAndGet()
                events += "window"
                // A second launch while the first window closes; the window then cannot open again.
                if (n == 1) { l.show(); WindowOutcome.CLOSED } else WindowOutcome.NO_WEBVIEW
            },
            busy = { false },
            askBackground = { true },
            tray = null,
            startHidden = false,
            toFront = { false },
            sleep = { events += "sleep" },
        )
        assertEquals(WindowOutcome.CLOSED, l.run())
        assertEquals(listOf("window", "window", "sleep", "window"), events.toList())
    }

    @Test
    fun aShowBeforeTheWindowIsUpIsAnsweredByThatWindow() {
        lateinit var l: WindowLoop
        l = loop(busy = false, answer = true, tray = FakeTray(), toFront = { events += "front"; false }, window = {
            windows.incrementAndGet()
            events += "window"
            // A second launch while the window is still coming up (toFront finds no window yet), then it is up.
            l.show()
            l.windowUp()
            WindowOutcome.CLOSED
        })
        assertEquals(WindowOutcome.CLOSED, l.run())
        assertEquals(1, windows.get())
    }

    @Test
    fun aShowBetweenTheXAndTheCloseOpensTheWindowAgain() {
        lateinit var l: WindowLoop
        l = loop(busy = false, answer = true, tray = FakeTray(), toFront = { events += "front"; false }, window = {
            val n = windows.incrementAndGet()
            events += "window"
            l.windowUp()
            // X was pressed: the window is gone, but the loop has not seen the close yet.
            if (n == 1) l.show()
            WindowOutcome.CLOSED
        })
        assertEquals(WindowOutcome.CLOSED, l.run())
        assertEquals(listOf("window", "front", "window"), events.toList())
    }

    @Test
    fun aShowThatReachesTheOpenWindowDoesNotOpenAnother() {
        lateinit var l: WindowLoop
        l = loop(busy = false, answer = true, tray = FakeTray(), window = {
            windows.incrementAndGet()
            events += "window"
            l.windowUp()
            l.show()
            WindowOutcome.CLOSED
        })
        assertEquals(WindowOutcome.CLOSED, l.run())
        assertEquals(listOf("window", "front"), events.toList())
    }
}

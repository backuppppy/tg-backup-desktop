package org.tgbackup.desktop

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The icon by the clock ([Tray]); it exists only while the program runs in the background without a window. */
interface TrayControl {
    /** Shows the icon; [onOpen] runs on a click or "פתח", [onQuit] on "יציאה". */
    fun showIcon(onOpen: () -> Unit, onQuit: () -> Unit)
    fun hideIcon()
}

/**
 * The window, closed and opened again while the program runs. After [window] closes: nothing [busy] → the end;
 * work running → [askBackground] (No → the end; Yes → the tray icon, until [show] opens the window again or
 * [quit] ends the program). Without a [tray] (`--smoke`, no system tray) closing the window always ends it.
 * [startHidden] (`--minimized`) starts in the tray. [show] and [quit] may come from any thread (the tray's EDT,
 * the server's `/internal/show`); a [show] while the window is open brings it to the front ([toFront]).
 */
class WindowLoop(
    private val window: () -> WindowOutcome,
    private val busy: () -> Boolean,
    private val askBackground: () -> Boolean,
    private val tray: TrayControl?,
    private val startHidden: Boolean,
    private val toFront: () -> Unit = {},
) {
    private enum class State { STARTING, OPEN, CLOSING, HIDDEN, ENDED }
    private enum class Request { SHOW, QUIT }

    private val lock = ReentrantLock()
    private val requested = lock.newCondition()
    private var state = State.STARTING
    private var request: Request? = null

    /** Blocks until the program should end; returns how the last window ended. */
    fun run(): WindowOutcome {
        val outcome = try {
            loop()
        } finally {
            lock.withLock { state = State.ENDED }
        }
        return outcome
    }

    private fun loop(): WindowOutcome {
        var hidden = startHidden && tray != null
        while (true) {
            if (!hidden) {
                lock.withLock {
                    state = State.OPEN
                    request = null
                }
                val outcome = window()
                lock.withLock { state = State.CLOSING }
                if (outcome != WindowOutcome.CLOSED) return outcome
                // A second launch that came while the window closed (or while the question was up) wins over the end.
                val background = tray != null && !showPending() && busy() && !showPending() && askBackground()
                if (!background) {
                    if (endUnlessShown()) return outcome
                    continue
                }
            }
            hidden = false
            if (waitInTray(tray!!) == Request.QUIT) return WindowOutcome.CLOSED
        }
    }

    private fun showPending(): Boolean = lock.withLock { request == Request.SHOW }

    /** Ends (a later [show] does nothing) unless a [show] is pending: then false, and the window opens again. */
    private fun endUnlessShown(): Boolean = lock.withLock {
        if (request == Request.SHOW) return false
        state = State.ENDED
        true
    }

    /** The icon until [show] or [quit]. A [show] that came while the question was up opens the window at once. */
    private fun waitInTray(tray: TrayControl): Request {
        lock.withLock {
            if (request == Request.SHOW) return Request.SHOW
            state = State.HIDDEN
        }
        // Without an icon the program could not be reached again: then it ends, as after No.
        val shown = runCatching { tray.showIcon(onOpen = ::show, onQuit = ::quit) }
            .onFailure { println("TRAY_FAIL ${it::class.java.simpleName}") }.isSuccess
        if (!shown) return Request.QUIT
        try {
            return lock.withLock {
                while (request == null) requested.await()
                request!!
            }
        } finally {
            // Also when the wait is interrupted: never an icon left behind by an ended loop.
            runCatching(tray::hideIcon)
        }
    }

    /** Opens the window (from the tray), or brings the open one to the front. */
    fun show() {
        val front = lock.withLock {
            when (state) {
                State.OPEN -> true
                State.STARTING, State.CLOSING, State.HIDDEN -> {
                    if (request == null) request = Request.SHOW
                    requested.signalAll()
                    false
                }
                State.ENDED -> false
            }
        }
        if (front) runCatching(toFront)
    }

    /** "יציאה": ends the program from the tray, without a question. */
    fun quit() {
        lock.withLock {
            if (state != State.HIDDEN) return
            request = Request.QUIT
            requested.signalAll()
        }
    }
}

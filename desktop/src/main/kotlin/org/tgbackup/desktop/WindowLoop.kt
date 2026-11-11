package org.tgbackup.desktop

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The icon by the clock ([Tray]); it exists only while the program runs in the background without a window. */
interface TrayControl {
    /** Shows the icon; [onOpen] runs on a click or "פתח", [onQuit] on "יציאה". */
    fun showIcon(onOpen: () -> Unit, onQuit: () -> Unit, notice: Pair<String, String>? = null)
    fun hideIcon()
}

/**
 * The window, closed and opened again while the program runs. After [window] closes: nothing [busy] → the end;
 * work running → the tray icon at once, without a question (until [show] opens the window again or [quit] ends
 *  * the program); the first time in a launch that the window is hidden by X the icon comes with a balloon
 * ([BACKGROUND_NOTICE]). Without a [tray] (`--smoke`, no system tray) closing the window always ends it.
 * [startHidden] (`--minimized`) starts in the tray, without a balloon. [show] and [quit] may come from any thread (the tray's EDT,
 * the server's `/internal/show`); a [show] while the window is open brings it to the front ([toFront], true when
 * a window was there to bring forward). [windowUp] tells the loop that the window it opened is on screen.
 *
 * Only the first window of the run may end the program when it fails (no WebView2, or it threw). A later one
 * (opened again from the tray or for a second launch) that fails is tried once more after [retryDelayMs]; if
 * that fails too the program goes back to the tray instead of ending (the engine keeps running).
 */
class WindowLoop(
    private val window: () -> WindowOutcome,
    private val busy: () -> Boolean,
    private val tray: TrayControl?,
    private val startHidden: Boolean,
    private val toFront: () -> Boolean = { false },
    private val retryDelayMs: Long = REOPEN_RETRY_MS,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private enum class State { STARTING, OPEN, CLOSING, HIDDEN, ENDED }
    private enum class Request { SHOW, QUIT }

    private val lock = ReentrantLock()
    private val requested = lock.newCondition()
    private var state = State.STARTING
    private var request: Request? = null

    /** The balloon still to be shown (once per launch). */
    private var notice: Pair<String, String>? = BACKGROUND_NOTICE

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
        var startedHidden = hidden
        var first = true
        while (true) {
            if (!hidden) {
                val outcome = if (first) openWindow() else reopen()
                first = false
                if (outcome == null) {
                    // Without an icon there is no way back in (`--smoke`): the end, as after a normal close.
                    if (tray == null) return WindowOutcome.CLOSED
                    // The window could not be opened again: back to the tray (the icon again), the way in.
                    println("WINDOW_BACK_TO_TRAY")
                    lock.withLock { request = null }
                    if (waitInTray(tray, withNotice = false) == Request.QUIT) return WindowOutcome.CLOSED
                    continue
                }
                if (outcome != WindowOutcome.CLOSED) return outcome
                // A second launch that came while the window closed wins over the end.
                val background = tray != null && !showPending() && busy() && !showPending()
                if (!background) {
                    if (endUnlessShown()) return outcome
                    continue
                }
            }
            hidden = false
            // The first wait of a `--minimized` start is not a hide by X: no balloon.
            val withNotice = !startedHidden
            startedHidden = false
            if (waitInTray(tray!!, withNotice = withNotice) == Request.QUIT) return WindowOutcome.CLOSED
        }
    }

    /** One window, from open to close (it blocks while it is up). */
    private fun openWindow(): WindowOutcome {
        lock.withLock {
            state = State.OPEN
            request = null
        }
        try {
            return window()
        } finally {
            lock.withLock { state = State.CLOSING }
        }
    }

    /** A later window: CLOSED once it was up and closed, null when it failed twice. */
    private fun reopen(): WindowOutcome? {
        for (attempt in 1..2) {
            val outcome = try {
                openWindow()
            } catch (t: Throwable) {
                if (t is InterruptedException) throw t
                println("WINDOW_REOPEN_FAIL ${t::class.java.simpleName}")
                null
            }
            if (outcome == WindowOutcome.CLOSED) return outcome
            if (outcome != null) println("WINDOW_REOPEN_FAIL $outcome")
            if (attempt == 1) sleep(retryDelayMs)
        }
        return null
    }

    /**
     * The window it opened is on screen (AppWindow calls it before its message loop): a [show] that came before
     * that is answered by it. A [show] after it either reaches the window ([toFront]) or is kept for the next one.
     */
    fun windowUp() {
        lock.withLock { if (state == State.OPEN) request = null }
    }

    private fun showPending(): Boolean = lock.withLock { request == Request.SHOW }

    /** Ends (a later [show] does nothing) unless a [show] is pending: then false, and the window opens again. */
    private fun endUnlessShown(): Boolean = lock.withLock {
        if (request == Request.SHOW) return false
        state = State.ENDED
        true
    }

    /** The icon until [show] or [quit]. A [show] that came before it was up opens the window at once. */
    private fun waitInTray(tray: TrayControl, withNotice: Boolean): Request {
        lock.withLock {
            if (request == Request.SHOW) return Request.SHOW
            state = State.HIDDEN
        }
        // Without an icon the program could not be reached again: then it ends, as after a close with nothing running.
        val balloon = if (withNotice) notice else null
        val shown = runCatching { tray.showIcon(onOpen = ::show, onQuit = ::quit, notice = balloon) }
            .onFailure { println("TRAY_FAIL ${it::class.java.simpleName}") }.isSuccess
        if (!shown) return Request.QUIT
        if (balloon != null) notice = null
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

    /**
     * Opens the window (from the tray), or brings the open one to the front. While a window is meant to be open the
     * request is still kept: the window may be closing already (X pressed) or not be on screen yet. It is dropped
     * once [toFront] reached a window that is still open, or once the window is up ([windowUp]).
     */
    fun show() {
        val front = lock.withLock {
            if (state == State.ENDED) return
            if (request == null) request = Request.SHOW
            requested.signalAll()
            state == State.OPEN
        }
        if (front && runCatching(toFront).getOrDefault(false)) {
            lock.withLock { if (state == State.OPEN && request == Request.SHOW) request = null }
        }
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

/** How long a failed reopen waits before its one retry (the last window's WebView2 may still be letting go). */
const val REOPEN_RETRY_MS = 1_500L

/** The balloon by the clock the first time the window goes away while work runs (title, text). */
val BACKGROUND_NOTICE = "TG Backup" to "TG Backup ממשיכה לפעול ברקע. לחיצה על הענן פותחת את החלון."

package org.tgbackup.desktop.spike

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Window size and title shared by both embedded-view candidates. */
internal const val WINDOW_TITLE = "TG Backup"
internal const val WINDOW_WIDTH = 1100
internal const val WINDOW_HEIGHT = 750

/**
 * The `--smoke` contract: exactly one of `PAGE_LOADED` (then the window is held for 5 s and the
 * process exits 0) or `PAGE_FAIL <reason>` (exit 1), with a 60 s overall timeout.
 * Exits use [Runtime.halt] so a native view's shutdown can never hang the result.
 */
internal class SpikeSmoke(private val smoke: Boolean) {
    private val decided = AtomicBoolean(false)

    fun startWatchdog() {
        thread(isDaemon = true, name = "smoke-watchdog") {
            Thread.sleep(TIMEOUT_MS)
            fail("timeout after ${TIMEOUT_MS / 1000}s")
        }
    }

    /** Reports the load once; in smoke mode calls [close] after the hold time, then exits 0. */
    fun loaded(close: () -> Unit) {
        if (!decided.compareAndSet(false, true)) return
        println("PAGE_LOADED")
        System.out.flush()
        if (!smoke) return
        thread(isDaemon = true, name = "smoke-hold") {
            Thread.sleep(HOLD_MS)
            runCatching(close)
            // close() normally ends the run loop; if native shutdown stalls, end hard.
            Thread.sleep(2_000)
            Runtime.getRuntime().halt(0)
        }
    }

    fun fail(reason: String) {
        if (!decided.compareAndSet(false, true)) return
        println("PAGE_FAIL ${reason.replace('\n', ' ')}")
        System.out.flush()
        Runtime.getRuntime().halt(1)
    }

    /** Called when the window's run loop returns or the window is closed. */
    fun finished() {
        if (!decided.get()) fail("window closed before the page loaded")
        System.out.flush()
        Runtime.getRuntime().halt(0)
    }

    companion object {
        const val TIMEOUT_MS = 60_000L
        const val HOLD_MS = 5_000L
    }
}

/**
 * Native libraries are loaded from an ASCII directory inside the install dir (passed by the
 * launcher as a system property), never extracted into TEMP: Windows loads DLLs through the
 * ANSI code page, so a Hebrew TEMP breaks them.
 */
internal fun nativeDir(property: String, fallback: String): File =
    File(System.getProperty(property) ?: fallback).absoluteFile

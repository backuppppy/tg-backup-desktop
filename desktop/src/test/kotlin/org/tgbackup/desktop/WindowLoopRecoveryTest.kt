package org.tgbackup.desktop

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowLoopRecoveryTest {
    private class FakeTray : TrayControl {
        @Volatile var onOpen: () -> Unit = {}
        @Volatile var onQuit: () -> Unit = {}
        @Volatile var visible = false

        override fun showIcon(onOpen: () -> Unit, onQuit: () -> Unit) {
            this.onOpen = onOpen
            this.onQuit = onQuit
            visible = true
        }

        override fun hideIcon() {
            visible = false
        }
    }

    private fun runRecovery(failReopen: () -> WindowOutcome) {
        val attempts = AtomicInteger()
        val tray = FakeTray()
        val loop = WindowLoop(
            window = {
                when (attempts.incrementAndGet()) {
                    1 -> WindowOutcome.CLOSED
                    2 -> failReopen()
                    else -> WindowOutcome.CLOSED
                }
            },
            busy = { attempts.get() == 1 },
            askBackground = { true },
            tray = tray,
            startHidden = false,
        )
        val result = CompletableFuture<WindowOutcome>()
        thread(isDaemon = true, name = "window-recovery-test") {
            runCatching { loop.run() }.fold(result::complete, result::completeExceptionally)
        }

        waitUntil { tray.visible }
        tray.onOpen()
        // Failed reopen must return to the tray instead of ending the app.
        waitUntil { tray.visible && attempts.get() == 2 }
        assertFalse(result.isDone, "the process should remain available in the tray")
        tray.onOpen()
        assertEquals(WindowOutcome.CLOSED, result.get(5, TimeUnit.SECONDS))
        assertEquals(3, attempts.get())
        assertFalse(tray.visible)
    }

    @Test
    fun missingWebViewOnTrayReopenReturnsToTrayAndCanBeRetried() =
        runRecovery { WindowOutcome.NO_WEBVIEW }

    @Test
    fun exceptionOnTrayReopenReturnsToTrayAndCanBeRetried() =
        runRecovery { throw IllegalStateException("temporary WebView startup failure") }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for tray recovery" }
            Thread.sleep(5)
        }
    }
}

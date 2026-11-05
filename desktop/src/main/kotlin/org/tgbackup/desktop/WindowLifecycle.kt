package org.tgbackup.desktop

/** Shown when WebView2 cannot be created. Plan 5 replaces it with a WebView2 install screen. */
const val MSG_NO_WEBVIEW = "לא ניתן לפתוח את החלון: רכיב WebView2 חסר במחשב."

enum class WindowOutcome { CLOSED, NO_WEBVIEW }

/** The window's life inside the running program: the engine never outlives the window, whatever happens. */
object WindowLifecycle {
    /**
     * Runs [open] (blocks while the window is up), then always [shutdown]. Returns the exit code: 0 after a normal
     * close, 1 when [open] threw, 2 when WebView2 was missing (then [tellNoWebView] runs, after the shutdown).
     */
    fun run(open: () -> WindowOutcome, shutdown: () -> Unit, tellNoWebView: () -> Unit): Int {
        var outcome: WindowOutcome? = null
        try {
            outcome = open()
        } catch (t: Throwable) {
            println("WINDOW_FAIL ${t::class.java.simpleName}")
        } finally {
            shutdown()
        }
        return when (outcome) {
            WindowOutcome.CLOSED -> 0
            WindowOutcome.NO_WEBVIEW -> {
                runCatching(tellNoWebView)
                2
            }
            null -> 1
        }
    }
}

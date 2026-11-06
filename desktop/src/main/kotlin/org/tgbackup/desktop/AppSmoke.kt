package org.tgbackup.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** The five bottom-navigation labels, in order (docs/ui-inventory.md). */
val TAB_LABELS = listOf("משימות", "ייצוא", "חשבונות", "לוג", "הגדרות")

/**
 * The `--smoke` run's data: a sibling of the real data dir (`TGBackup-smoke` next to `TGBackup`), wiped on every
 * smoke, so the smoke never reads or writes the user's own accounts and jobs.
 */
fun smokeDataDir(real: Path): Path = real.resolveSibling(real.fileName.toString() + "-smoke")

/** Checks the page report sent by [SmokePage.SCRIPT] through the `tgbLoaded` binding. */
object SmokePage {
    const val BINDING = "tgbLoaded"

    /** Null when the report shows the real screen: our server, `#screen` rendered, all five tabs. Else the reason. */
    fun problem(bindArgs: String?): String? {
        val report = runCatching { (Json.parseToJsonElement(bindArgs ?: "") as JsonArray).first() as JsonObject }
            .getOrNull() ?: return "bad report"
        val href = (report["href"] as? JsonPrimitive)?.content.orEmpty()
        if (!href.startsWith("http://127.0.0.1:")) return "unexpected page"
        if ((report["screen"] as? JsonPrimitive)?.booleanOrNull != true) return "no #screen content"
        val tabs = (report["tabs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }.orEmpty()
        val missing = TAB_LABELS.filter { it !in tabs }
        return if (missing.isEmpty()) null else "missing tabs: ${missing.joinToString(", ")}"
    }

    /**
     * After `load`, waits (up to 20 s) until the shell has rendered a screen with its buttons, then reports the
     * page: its URL, whether `#screen` has content, and the tab labels.
     */
    val SCRIPT = """
        window.addEventListener('load', function () {
          var started = Date.now();
          (function check() {
            var screen = document.getElementById('screen');
            var ready = !!(screen && screen.querySelector('button'));
            if (!ready && Date.now() - started < 20000) { setTimeout(check, 100); return; }
            var tabs = Array.prototype.map.call(
              document.querySelectorAll('nav.tabs .label'), function (e) { return e.textContent; });
            window.$BINDING({ href: location.href, screen: ready, tabs: tabs });
          })();
        });
    """.trimIndent()
}

/** Runs [steps] in order on a background thread; when they take longer than [limitMs], calls [halt] (0). */
object Shutdown {
    fun run(steps: List<() -> Unit>, limitMs: Long, halt: (Int) -> Unit = { Runtime.getRuntime().halt(it) }): Boolean {
        val worker = thread(isDaemon = true, name = "shutdown") {
            for (step in steps) runCatching(step).onFailure { println("SHUTDOWN_STEP_FAIL ${it::class.java.simpleName}") }
        }
        worker.join(limitMs)
        if (!worker.isAlive) return true
        println("SHUTDOWN_TIMEOUT after ${limitMs / 1000}s")
        System.out.flush()
        halt(0)
        return false
    }
}

/**
 * The `--smoke` contract: exactly one of `PAGE_LOADED` (then the window is held for [HOLD_MS] and closed) or
 * `PAGE_FAIL <reason>` (exit 1), with a [TIMEOUT_MS] overall timeout. `TGB_SMOKE_HOLD_MS` holds the window longer
 * (CI's second screenshot, of the maximised window), see [holdMs]. Exits use [Runtime.halt] so a native view's
 * or the engine's shutdown can never hang the result. Without `--smoke` only [loaded]'s line is printed.
 */
class AppSmoke(val enabled: Boolean) {
    private val decided = AtomicBoolean(false)

    fun startWatchdog() {
        if (!enabled) return
        thread(isDaemon = true, name = "smoke-watchdog") {
            Thread.sleep(TIMEOUT_MS)
            fail("timeout after ${TIMEOUT_MS / 1000}s")
        }
    }

    /** Reports the load once; in smoke mode calls [close] after the hold time, and ends hard if shutdown stalls. */
    fun loaded(close: () -> Unit) {
        if (!decided.compareAndSet(false, true)) return
        println("PAGE_LOADED")
        System.out.flush()
        if (!enabled) return
        thread(isDaemon = true, name = "smoke-hold") {
            Thread.sleep(holdMs(System.getenv("TGB_SMOKE_HOLD_MS")))
            runCatching(close)
            // close() ends the run loop and main shuts the engine down; if that stalls, end hard.
            Thread.sleep(SHUTDOWN_GRACE_MS)
            println("SMOKE_SHUTDOWN_SLOW")
            System.out.flush()
            Runtime.getRuntime().halt(0)
        }
    }

    fun fail(reason: String) {
        val line = "PAGE_FAIL ${reason.replace('\n', ' ').take(400)}"
        if (!enabled) {
            println(line)
            return
        }
        if (!decided.compareAndSet(false, true)) return
        println(line)
        System.out.flush()
        Runtime.getRuntime().halt(1)
    }

    /** Smoke mode, after the window closed and the engine shut down: exit 0, or 1 when the page never loaded. */
    fun finished() {
        if (!enabled) return
        if (!decided.get()) fail("window closed before the page loaded")
        System.out.flush()
        Runtime.getRuntime().halt(0)
    }

    companion object {
        const val TIMEOUT_MS = 75_000L
        const val HOLD_MS = 5_000L
        const val MAX_HOLD_MS = 60_000L

        /** The hold time: `TGB_SMOKE_HOLD_MS` when it is a positive number (at most [MAX_HOLD_MS]), else [HOLD_MS]. */
        fun holdMs(env: String?): Long =
            env?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.coerceAtMost(MAX_HOLD_MS) ?: HOLD_MS
        const val SHUTDOWN_GRACE_MS = 20_000L
    }
}

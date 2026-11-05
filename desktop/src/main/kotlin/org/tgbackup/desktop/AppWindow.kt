package org.tgbackup.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * The system WebView2 (Edge) runtime, driven through the `webview` C library (webview.dll + WebView2Loader.dll,
 * taken at build time from com.github.webview:webview_java:1.3.0) with our own small JNA binding. The DLLs live
 * in `<install>\app\natives` (`-Dtgb.natives`).
 */
internal interface WebviewLib : Library {
    fun webview_create(debug: Int, window: Pointer?): Pointer?
    fun webview_destroy(w: Pointer)
    fun webview_get_window(w: Pointer): Pointer?
    fun webview_run(w: Pointer)
    fun webview_terminate(w: Pointer)
    fun webview_dispatch(w: Pointer, fn: DispatchFn, arg: Pointer?)
    fun webview_set_title(w: Pointer, title: String)
    fun webview_set_size(w: Pointer, width: Int, height: Int, hints: Int)
    fun webview_navigate(w: Pointer, url: String)
    fun webview_init(w: Pointer, js: String)
    fun webview_bind(w: Pointer, name: String, fn: BindFn, arg: Pointer?)
    fun webview_return(w: Pointer, seq: String, status: Int, result: String)
}

internal fun interface DispatchFn : Callback {
    fun invoke(w: Pointer?, arg: Pointer?)
}

internal fun interface BindFn : Callback {
    fun invoke(seq: String?, req: String?, arg: Pointer?)
}

private interface Kernel32Env : Library {
    fun SetEnvironmentVariableW(name: WString, value: WString?): Boolean
}

const val WINDOW_TITLE = "TG Backup"
const val WINDOW_WIDTH = 1100
const val WINDOW_HEIGHT = 750

private const val WEBVIEW_HINT_NONE = 0
private const val SWP_NOZORDER = 0x0004
private const val SWP_NOACTIVATE = 0x0010

/** The program's window: the screens from the internal server, inside WebView2. */
object AppWindow {
    /** Strong references so JNA callbacks are not collected while native code holds them. */
    private val keepAlive = mutableListOf<Any>()

    /**
     * Opens [url] and blocks until the window is closed. The WebView2 profile goes to [profileDir] (otherwise it
     * would sit next to TGBackup.exe, read-only under Program Files). [onRejectedLink] is told (without the URL)
     * when the page asked to open a link that is not http(s).
     */
    fun open(url: String, smoke: AppSmoke, profileDir: Path, onRejectedLink: () -> Unit = {}) {
        val natives = File(System.getProperty("tgb.natives") ?: "natives").absoluteFile
        val kernel32 = Native.load("kernel32", Kernel32Env::class.java)
        val userData = profileDir.toFile().apply { mkdirs() }
        kernel32.SetEnvironmentVariableW(WString("WEBVIEW2_USER_DATA_FOLDER"), WString(userData.path))
        System.getenv("TGB_FORCE_PROXY")?.takeIf { it.isNotBlank() }?.let {
            kernel32.SetEnvironmentVariableW(
                WString("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS"), WString("--proxy-server=$it"),
            )
        }

        // Load the loader first by full path so webview.dll's import of it resolves to this copy.
        NativeLibrary.getInstance(File(natives, "WebView2Loader.dll").path)
        val lib = Native.load(
            File(natives, "webview.dll").path,
            WebviewLib::class.java,
            mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"),
        )

        val w = lib.webview_create(0, null)
            ?: return smoke.fail("webview_create returned null (WebView2 runtime missing?)")
        val hwnd = lib.webview_get_window(w)?.let(::HWND)
        val terminate = DispatchFn { view, _ -> view?.let(lib::webview_terminate) }
        val openExternal = BindFn { seq, req, _ ->
            val target = ExternalLinks.urlFromBindArgs(req)
            if (target != null && ExternalLinks.allowed(target)) {
                browse(target)
            } else {
                println("EXTERNAL_LINK_REJECTED")
                runCatching(onRejectedLink)
            }
            if (seq != null) lib.webview_return(w, seq, 0, "null")
        }
        val onLoad = BindFn { seq, req, _ ->
            if (seq != null) lib.webview_return(w, seq, 0, "null")
            val problem = SmokePage.problem(req)
            if (problem != null) return@BindFn smoke.fail(problem)
            hwnd?.let(::reportPlacement)
            smoke.loaded { lib.webview_dispatch(w, terminate, null) }
        }
        keepAlive.addAll(listOf(terminate, openExternal, onLoad))

        lib.webview_set_title(w, WINDOW_TITLE)
        lib.webview_set_size(w, WINDOW_WIDTH, WINDOW_HEIGHT, WEBVIEW_HINT_NONE)
        hwnd?.let(::centreOnScreen) ?: println("WINDOW_FIT skipped (no HWND)")
        lib.webview_bind(w, ExternalLinks.BINDING, openExternal, null)
        lib.webview_init(w, ExternalLinks.INIT_SCRIPT)
        if (smoke.enabled) {
            lib.webview_bind(w, SmokePage.BINDING, onLoad, null)
            lib.webview_init(w, SmokePage.SCRIPT)
        }
        lib.webview_navigate(w, url)
        lib.webview_run(w)
        lib.webview_destroy(w)
    }

    /** Opens [url] in the default browser, off the window's thread (Desktop.browse can take a while). */
    private fun browse(url: String) {
        thread(isDaemon = true, name = "open-external") {
            val ok = runCatching {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI(url))
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            if (!ok) {
                // Fallback without AWT: the shell's URL handler. The URL is one argument, never a command line.
                runCatching { ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start() }
                    .onFailure { println("EXTERNAL_LINK_OPEN_FAIL ${it::class.java.simpleName}") }
            }
        }
    }

    private fun workAreaOf(hwnd: HWND): ScreenRect {
        val monitor = User32.INSTANCE.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST)
        val info = WinUser.MONITORINFO()
        check(User32.INSTANCE.GetMonitorInfo(monitor, info).booleanValue()) { "GetMonitorInfo failed" }
        return info.rcWork.toScreenRect()
    }

    private fun windowRectOf(hwnd: HWND): ScreenRect {
        val r = RECT()
        check(User32.INSTANCE.GetWindowRect(hwnd, r)) { "GetWindowRect failed" }
        return r.toScreenRect()
    }

    private fun RECT.toScreenRect() = ScreenRect(left, top, right, bottom)

    /**
     * webview_set_size only sizes the window; Windows picks its position, so on a small screen part of the
     * window can end up off screen. Uses the real outer size (borders, DPI scaling) and the work area of
     * the monitor the window is on, then centres it there, shrinking a side that does not fit.
     */
    private fun centreOnScreen(hwnd: HWND) {
        val work = workAreaOf(hwnd)
        val current = windowRectOf(hwnd)
        val target = WindowFit.fit(work, current.width, current.height)
        User32.INSTANCE.SetWindowPos(
            hwnd, null, target.left, target.top, target.width, target.height, SWP_NOZORDER or SWP_NOACTIVATE,
        )
        println("WINDOW_FIT work=$work from=$current to=$target")
    }

    /** Smoke evidence: where the window really is once the page has loaded. */
    private fun reportPlacement(hwnd: HWND) {
        val work = workAreaOf(hwnd)
        val rect = windowRectOf(hwnd)
        println("WINDOW_RECT $rect WORK_AREA $work")
        println("WINDOW_INSIDE ${if (rect.isInside(work)) "yes" else "no"}")
    }
}

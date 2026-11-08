package org.tgbackup.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HICON
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
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

/**
 * The library's UTF-8 option does not cover callback arguments (JNA decodes those with the platform charset,
 * cp1252 on an English Windows), so they arrive as raw pointers and are read as UTF-8 by [utf8].
 */
internal fun interface BindFn : Callback {
    fun invoke(seq: Pointer?, req: Pointer?, arg: Pointer?)
}

private fun Pointer?.utf8(): String? = this?.getString(0, "UTF-8")

private interface Kernel32Env : Library {
    fun SetEnvironmentVariableW(name: WString, value: WString?): Boolean
}

/** What jna-platform's User32 lacks. */
internal interface User32More : Library {
    fun IsIconic(hwnd: HWND): Boolean
    fun AllowSetForegroundWindow(processId: Int): Boolean
    fun SetProcessDpiAwarenessContext(context: Pointer): Boolean

    companion object {
        val INSTANCE: User32More by lazy { Native.load("user32", User32More::class.java) }
    }
}

const val WINDOW_TITLE = "TG Backup"
const val WINDOW_WIDTH = 1100
const val WINDOW_HEIGHT = 750

private const val WEBVIEW_HINT_NONE = 0
private const val SWP_NOZORDER = 0x0004
private const val SWP_NOACTIVATE = 0x0010
private const val PM_REMOVE = 0x0001
private const val IMAGE_ICON = 1
private const val LR_LOADFROMFILE = 0x0010
private const val WM_SETICON = 0x0080
private const val ICON_SMALL = 0
private const val ICON_BIG = 1
private const val SM_CXICON = 11
private const val SM_CYICON = 12
private const val SM_CXSMICON = 49
private const val SM_CYSMICON = 50

/** The program icon (tools/make-icons.py), shipped next to the DLLs in `<install>\app\natives`. */
const val WINDOW_ICON_FILE = "TGBackup.ico"

/** The program's window: the screens from the internal server, inside WebView2. */
object AppWindow {
    /** Strong references so JNA callbacks are not collected while native code holds them. */
    private val keepAlive = mutableListOf<Any>()

    /** The open window, for [toFront]; null while there is none. */
    @Volatile
    private var current: HWND? = null

    /**
     * Opens [url] and blocks until the window is closed ([WindowOutcome.CLOSED]); returns
     * [WindowOutcome.NO_WEBVIEW] at once when WebView2 cannot be created. The WebView2 profile goes to [profileDir]
     * (otherwise it would sit next to TGBackup.exe, read-only under Program Files). [onRejectedLink] is told
     * (without the URL) when our page asked to open a link that is not http(s).
     */
    fun open(url: String, smoke: AppSmoke, profileDir: Path, onRejectedLink: () -> Unit = {}): WindowOutcome {
        dropStrayQuit()
        val origin = checkNotNull(ExternalLinks.originOf(url)) { "entry URL is not http" }
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
        if (w == null) {
            smoke.fail("webview_create returned null (WebView2 runtime missing?)")
            println("WEBVIEW_MISSING")
            return WindowOutcome.NO_WEBVIEW
        }
        val hwnd = lib.webview_get_window(w)?.let(::HWND)
        // webview_dispatch must never reach a destroyed view (the smoke's close comes from another thread).
        val alive = AtomicBoolean(true)
        val terminate = DispatchFn { view, _ -> view?.let(lib::webview_terminate) }
        val close = { synchronized(alive) { if (alive.get()) lib.webview_dispatch(w, terminate, null) } }
        val openExternal = BindFn { seqPtr, reqPtr, _ ->
            when (val d = ExternalLinks.decide(reqPtr.utf8(), origin)) {
                is LinkDecision.Open -> browse(d.url)
                LinkDecision.Rejected -> {
                    println("EXTERNAL_LINK_REJECTED")
                    runCatching(onRejectedLink)
                }
                LinkDecision.WrongOrigin -> println("EXTERNAL_LINK_WRONG_ORIGIN")
            }
            seqPtr.utf8()?.let { lib.webview_return(w, it, 0, "null") }
        }
        // A document that is not ours (a navigation the init script could not catch): back home at once.
        val onPage = BindFn { seqPtr, reqPtr, _ ->
            seqPtr.utf8()?.let { lib.webview_return(w, it, 0, "null") }
            val href = ExternalLinks.hrefFromBindArgs(reqPtr.utf8()) ?: return@BindFn
            val action = ExternalLinks.onPage(href, origin)
            if (action is PageAction.GoHome) {
                println("FOREIGN_PAGE_LEFT")
                lib.webview_navigate(w, url)
                action.external?.let(::browse)
            }
        }
        val onLoad = BindFn { seqPtr, reqPtr, _ ->
            seqPtr.utf8()?.let { lib.webview_return(w, it, 0, "null") }
            val problem = SmokePage.problem(reqPtr.utf8())
            if (problem != null) return@BindFn smoke.fail(problem)
            hwnd?.let(::reportPlacement)
            smoke.loaded(close)
        }
        val callbacks = listOf(terminate, openExternal, onPage, onLoad)
        keepAlive.addAll(callbacks)

        lib.webview_set_title(w, WINDOW_TITLE)
        lib.webview_set_size(w, WINDOW_WIDTH, WINDOW_HEIGHT, WEBVIEW_HINT_NONE)
        hwnd?.let(::centreOnScreen) ?: println("WINDOW_FIT skipped (no HWND)")
        hwnd?.let { setIcon(it, File(natives, WINDOW_ICON_FILE)) }
        lib.webview_bind(w, ExternalLinks.BINDING, openExternal, null)
        lib.webview_bind(w, ExternalLinks.PAGE_BINDING, onPage, null)
        lib.webview_init(w, ExternalLinks.initScript(origin))
        if (smoke.enabled) {
            lib.webview_bind(w, SmokePage.BINDING, onLoad, null)
            lib.webview_init(w, SmokePage.SCRIPT)
        }
        lib.webview_navigate(w, url)
        // Opened again from the tray or for a second launch: in front, not behind the window that was active.
        hwnd?.let(User32.INSTANCE::SetForegroundWindow)
        current = hwnd
        lib.webview_run(w)
        current = null
        synchronized(alive) { alive.set(false) }
        lib.webview_destroy(w)
        keepAlive.removeAll(callbacks)
        return WindowOutcome.CLOSED
    }

    /**
     * Brings the open window to the front (a second launch, or "פתח" while it is open): restored when minimized,
     * otherwise left as it is (a maximized window stays maximized). Any thread; nothing when no window is open.
     */
    fun toFront() {
        val hwnd = current ?: return
        val restore = runCatching { User32More.INSTANCE.IsIconic(hwnd) }.getOrDefault(false)
        User32.INSTANCE.ShowWindow(hwnd, if (restore) WinUser.SW_RESTORE else WinUser.SW_SHOW)
        User32.INSTANCE.SetForegroundWindow(hwnd)
    }

    /**
     * The window is opened again on the same thread. A quit request the previous window's end left behind
     * would end the new message loop at once, so it is taken off the thread's queue first.
     */
    private fun dropStrayQuit() {
        val msg = WinUser.MSG()
        var dropped = 0
        while (User32.INSTANCE.PeekMessage(msg, null, WinUser.WM_QUIT, WinUser.WM_QUIT, PM_REMOVE)) dropped++
        if (dropped > 0) println("WINDOW_STRAY_QUIT $dropped")
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

    /** The window icon's big and small HICONs, loaded once and kept for the whole process (never destroyed). */
    private class WindowIcons(val big: Pointer, val small: Pointer)

    /** Window thread only ([open] always runs there); null until the .ico has loaded. */
    private var icons: WindowIcons? = null

    /**
     * The .ico's frames at the system's big and small icon sizes (Windows picks the closest frame), loaded on the
     * first window and reused by every window opened later (from the tray, a second launch), so reopening does not
     * load new handles. A half load is undone and tried again next time.
     */
    private fun loadIcons(ico: File): WindowIcons {
        icons?.let { return it }
        check(ico.isFile) { "${ico.name} not found" }
        val u = User32.INSTANCE
        fun load(cx: Int, cy: Int): Pointer = checkNotNull(
            u.LoadImage(null, ico.path, IMAGE_ICON, u.GetSystemMetrics(cx), u.GetSystemMetrics(cy), LR_LOADFROMFILE),
        ) { "LoadImageW failed (${Native.getLastError()})" }.pointer
        val big = load(SM_CXICON, SM_CYICON)
        val small = runCatching { load(SM_CXSMICON, SM_CYSMICON) }
            .onFailure { u.DestroyIcon(HICON(big)) }
            .getOrThrow()
        return WindowIcons(big, small).also { icons = it }
    }

    /**
     * The title bar and Alt+Tab icon. Without it the window shows the WebView host's default icon; a missing or
     * broken file only prints WINDOW_ICON fail and never stops the window.
     */
    private fun setIcon(hwnd: HWND, ico: File) {
        runCatching {
            val i = loadIcons(ico)
            val u = User32.INSTANCE
            u.SendMessage(hwnd, WM_SETICON, WPARAM(ICON_BIG.toLong()), LPARAM(Pointer.nativeValue(i.big)))
            u.SendMessage(hwnd, WM_SETICON, WPARAM(ICON_SMALL.toLong()), LPARAM(Pointer.nativeValue(i.small)))
        }.onSuccess { println("WINDOW_ICON ok") }
            .onFailure { println("WINDOW_ICON fail ${it.message ?: it::class.java.simpleName}") }
    }

    /** Smoke evidence: where the window really is once the page has loaded. */
    private fun reportPlacement(hwnd: HWND) {
        val work = workAreaOf(hwnd)
        val rect = windowRectOf(hwnd)
        println("WINDOW_RECT $rect WORK_AREA $work")
        println("WINDOW_INSIDE ${if (rect.isInside(work)) "yes" else "no"}")
    }
}

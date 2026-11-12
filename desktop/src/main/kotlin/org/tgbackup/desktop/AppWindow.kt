package org.tgbackup.desktop

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
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
import com.sun.jna.ptr.PointerByReference
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

/**
 * The window's own icon (tools/make-icons.py): the tray's white cloud, for the taskbar button (also pinned), the
 * title bar and Alt+Tab. Shipped next to the DLLs in `<install>\app\natives`; the program's icon (exe, shortcuts,
 * Add/Remove Programs) stays the BACKUP logo, TGBackup.ico.
 */
const val WINDOW_ICON_FILE = "TGBackup-window.ico"

/** shell32's property store of a window (taskbar identity and what a pinned button relaunches). */
private interface Shell32Window : Library {
    fun SHGetPropertyStoreForWindow(hwnd: HWND, riid: Pointer, ppv: PointerByReference): Int
}

/**
 * The window's AppUserModel properties. A pinned taskbar button takes its icon from RelaunchIconResource only while
 * the window's AppUserModel.ID is set, so the ID comes first (all are written and committed together). Pinning from
 * the open window shows the cloud; the shortcuts deliberately carry no ID, which would turn the window's button back
 * into the logo.
 */
object WindowAppProperties {
    const val APP_ID = "TGBackup.Desktop"
    const val PID_ID = 5
    const val PID_RELAUNCH_COMMAND = 2
    const val PID_RELAUNCH_ICON = 3
    const val PID_RELAUNCH_DISPLAY_NAME = 4

    /** (pid, value) in write order, or null without the installed exe ([exe] = jpackage.app-path). */
    fun of(exe: String?, natives: File): List<Pair<Int, String>>? {
        if (exe.isNullOrBlank()) return null
        return listOf(
            PID_ID to APP_ID,
            PID_RELAUNCH_COMMAND to "\"$exe\"",
            PID_RELAUNCH_DISPLAY_NAME to WINDOW_TITLE,
            PID_RELAUNCH_ICON to "${File(natives, WINDOW_ICON_FILE).path},0",
        )
    }
}

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
     * (without the URL) when our page asked to open a link that is not http(s). [onUp] runs once the window is on
     * screen and [toFront] can reach it.
     */
    fun open(
        url: String,
        smoke: AppSmoke,
        profileDir: Path,
        onUp: () -> Unit = {},
        onRejectedLink: () -> Unit = {},
    ): WindowOutcome {
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
        hwnd?.let { setAppProperties(it, natives) }
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
        try {
            runCatching(onUp)
            lib.webview_run(w)
        } finally {
            current = null
            synchronized(alive) { alive.set(false) }
            lib.webview_destroy(w)
            keepAlive.removeAll(callbacks)
        }
        return WindowOutcome.CLOSED
    }

    /**
     * Brings the open window to the front (a second launch, or "פתח" while it is open): restored when minimized,
     * otherwise left as it is (a maximized window stays maximized). Any thread. False when no window is open (not
     * on screen yet, or already closing): the caller then keeps the request.
     */
    fun toFront(): Boolean {
        val hwnd = current ?: return false
        val restore = runCatching { User32More.INSTANCE.IsIconic(hwnd) }.getOrDefault(false)
        User32.INSTANCE.ShowWindow(hwnd, if (restore) WinUser.SW_RESTORE else WinUser.SW_SHOW)
        User32.INSTANCE.SetForegroundWindow(hwnd)
        // Closed while we were at it: the request stays, the loop opens the window again.
        return current == hwnd
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
     * The taskbar button, title bar and Alt+Tab icon (the cloud). Without it the window shows the WebView host's default icon; a missing or
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

    /**
     * The window's taskbar identity and what its pinned button relaunches, with the cloud as that button's icon
     * ([WindowAppProperties]). A failure only prints WINDOW_APPID fail and never stops the window.
     */
    private fun setAppProperties(hwnd: HWND, natives: File) {
        val props = WindowAppProperties.of(System.getProperty("jpackage.app-path"), natives)
            ?: return println("WINDOW_APPID skipped (no jpackage.app-path)")
        runCatching { writeAppProperties(hwnd, props) }
            .onSuccess { println("WINDOW_APPID ok") }
            .onFailure { println("WINDOW_APPID fail ${it.message ?: it::class.java.simpleName}") }
    }

    /**
     * Writes [props] (pid → text) to the window's IPropertyStore and commits them together. By hand,
     * as jna-platform has no PROPVARIANT: vtable Release = 2, SetValue = 6, Commit = 7.
     */
    private fun writeAppProperties(hwnd: HWND, props: List<Pair<Int, String>>) {
        val out = PointerByReference()
        val hr = shell32.SHGetPropertyStoreForWindow(hwnd, guid(IID_IPROPERTY_STORE), out)
        check(hr >= 0 && out.value != null) { "SHGetPropertyStoreForWindow 0x%08x".format(hr) }
        val store = out.value
        try {
            for ((pid, value) in props) {
                // PROPERTYKEY: fmtid (GUID) + pid (DWORD).
                val key = Memory(20).apply { write(0, guid(FMTID_APP_USER_MODEL).getByteArray(0, 16), 0, 16) }
                key.setInt(16, pid)
                // PROPVARIANT (24 bytes on x64): vt = VT_LPWSTR, three reserved WORDs, then the PWSTR at 8.
                val text = Memory((value.length + 1) * 2L).apply { setWideString(0, value) }
                val pv = Memory(24).apply { clear(); setShort(0, VT_LPWSTR); setPointer(8, text) }
                val r = comCall(store, VTBL_SET_VALUE, key, pv)
                // Only pv points at text: keep it (and its native memory) alive until SetValue has copied it.
                java.lang.ref.Reference.reachabilityFence(text)
                check(r >= 0) { "SetValue $pid 0x%08x".format(r) }
            }
            val r = comCall(store, VTBL_COMMIT)
            check(r >= 0) { "Commit 0x%08x".format(r) }
        } finally {
            comCall(store, VTBL_RELEASE)
        }
    }

    /** Calls method [index] of the COM object [obj]'s vtable with [args] after `this`; returns the HRESULT. */
    private fun comCall(obj: Pointer, index: Int, vararg args: Any): Int {
        val fn = obj.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE)
        return Function.getFunction(fn).invokeInt(arrayOf<Any>(obj, *args))
    }

    /** A GUID ("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx") in its in-memory layout: Data1..3 little-endian, Data4 as is. */
    private fun guid(text: String): Memory {
        val h = text.replace("-", "")
        val m = Memory(16)
        m.setInt(0, h.substring(0, 8).toLong(16).toInt())
        m.setShort(4, h.substring(8, 12).toInt(16).toShort())
        m.setShort(6, h.substring(12, 16).toInt(16).toShort())
        for (i in 0 until 8) m.setByte(8L + i, h.substring(16 + 2 * i, 18 + 2 * i).toInt(16).toByte())
        return m
    }

    private val shell32: Shell32Window by lazy { Native.load("shell32", Shell32Window::class.java) }

    private const val IID_IPROPERTY_STORE = "886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"
    private const val FMTID_APP_USER_MODEL = "9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"
    private const val VT_LPWSTR: Short = 31
    private const val VTBL_RELEASE = 2
    private const val VTBL_SET_VALUE = 6
    private const val VTBL_COMMIT = 7

    /** Smoke evidence: where the window really is once the page has loaded. */
    private fun reportPlacement(hwnd: HWND) {
        val work = workAreaOf(hwnd)
        val rect = windowRectOf(hwnd)
        println("WINDOW_RECT $rect WORK_AREA $work")
        println("WINDOW_INSIDE ${if (rect.isInside(work)) "yes" else "no"}")
    }
}

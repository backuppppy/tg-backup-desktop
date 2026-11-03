package org.tgbackup.desktop.spike

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import org.tgbackup.desktop.AppDirs
import java.io.File

/**
 * Candidate A: the system WebView2 (Edge) runtime, driven through the `webview` C library
 * (webview.dll + WebView2Loader.dll, taken at build time from com.github.webview:webview_java:1.3.0)
 * with our own small JNA binding. The DLLs live in `<install>\app\natives` (`-Dtgb.natives`).
 */
internal interface WebviewLib : Library {
    fun webview_create(debug: Int, window: Pointer?): Pointer?
    fun webview_destroy(w: Pointer)
    fun webview_run(w: Pointer)
    fun webview_terminate(w: Pointer)
    fun webview_dispatch(w: Pointer, fn: DispatchFn, arg: Pointer?)
    fun webview_set_title(w: Pointer, title: String)
    fun webview_set_size(w: Pointer, width: Int, height: Int, hints: Int)
    fun webview_navigate(w: Pointer, url: String)
    fun webview_init(w: Pointer, js: String)
    fun webview_bind(w: Pointer, name: String, fn: BindFn, arg: Pointer?)
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

private const val WEBVIEW_HINT_NONE = 0

/** Strong references so JNA callbacks are not collected while native code holds them. */
private val keepAlive = mutableListOf<Any>()

fun main(args: Array<String>) {
    val smoke = SpikeSmoke("--smoke" in args)
    smoke.startWatchdog()
    try {
        run(smoke)
    } catch (t: Throwable) {
        smoke.fail("${t::class.java.simpleName}: ${t.message}")
    }
    smoke.finished()
}

private fun run(smoke: SpikeSmoke) {
    val natives = nativeDir("tgb.natives", "natives")
    val kernel32 = Native.load("kernel32", Kernel32Env::class.java)
    // WebView2 would otherwise put its profile next to TGBackup.exe (read-only under Program Files).
    val userData = AppDirs.dataDir().resolve("webview2").toFile().apply { mkdirs() }
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

    val server = LocalPageServer()
    val url = "http://127.0.0.1:${server.start()}/"

    val w = lib.webview_create(0, null)
        ?: return smoke.fail("webview_create returned null (WebView2 runtime missing?)")
    val terminate = DispatchFn { view, _ -> view?.let(lib::webview_terminate) }
    val onLoad = BindFn { _, req, _ ->
        if (req != null && req.contains("127.0.0.1")) {
            smoke.loaded { lib.webview_dispatch(w, terminate, null) }
        }
    }
    keepAlive.addAll(listOf(terminate, onLoad))

    lib.webview_set_title(w, WINDOW_TITLE)
    lib.webview_set_size(w, WINDOW_WIDTH, WINDOW_HEIGHT, WEBVIEW_HINT_NONE)
    lib.webview_bind(w, "tgbLoaded", onLoad, null)
    lib.webview_init(
        w,
        "window.addEventListener('load', function () { window.tgbLoaded(location.href); });",
    )
    lib.webview_navigate(w, url)
    lib.webview_run(w)
    lib.webview_destroy(w)
    server.stop()
}

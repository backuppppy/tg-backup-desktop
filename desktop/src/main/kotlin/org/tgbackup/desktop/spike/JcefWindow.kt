package org.tgbackup.desktop.spike

import me.friwi.jcefmaven.CefAppBuilder
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.tgbackup.desktop.AppDirs
import java.awt.BorderLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * Candidate B: Chromium via JCEF (me.friwi:jcefmaven). The CEF binaries are unpacked at build time
 * into `<install>\app\jcef` (`-Dtgb.jcef`); installation/download is disabled at runtime.
 */
fun main(args: Array<String>) {
    val smoke = SpikeSmoke("--smoke" in args)
    smoke.startWatchdog()
    try {
        run(smoke)
    } catch (t: Throwable) {
        smoke.fail("${t::class.java.simpleName}: ${t.message}")
    }
}

private fun run(smoke: SpikeSmoke) {
    val server = LocalPageServer()
    val url = "http://127.0.0.1:${server.start()}/"

    val builder = CefAppBuilder()
    builder.setInstallDir(nativeDir("tgb.jcef", "jcef"))
    builder.setSkipInstallation(true) // never fetch natives at runtime
    builder.setMirrors(emptyList())
    builder.cefSettings.windowless_rendering_enabled = false
    builder.cefSettings.root_cache_path = AppDirs.dataDir().resolve("cef").toString()
    System.getenv("TGB_FORCE_PROXY")?.takeIf { it.isNotBlank() }?.let {
        builder.addJcefArgs("--proxy-server=$it")
    }
    val app = builder.build()
    val client = app.createClient()
    lateinit var frame: JFrame
    client.addLoadHandler(object : CefLoadHandlerAdapter() {
        override fun onLoadEnd(browser: CefBrowser, cefFrame: CefFrame, httpStatusCode: Int) {
            if (!cefFrame.isMain || !browser.url.startsWith(url)) return
            if (httpStatusCode != 200) return smoke.fail("HTTP $httpStatusCode")
            smoke.loaded {
                SwingUtilities.invokeLater { frame.dispose() }
            }
        }

        override fun onLoadError(
            browser: CefBrowser,
            cefFrame: CefFrame,
            errorCode: CefLoadHandler.ErrorCode,
            errorText: String?,
            failedUrl: String?,
        ) {
            if (cefFrame.isMain && errorCode != CefLoadHandler.ErrorCode.ERR_ABORTED) {
                smoke.fail("$errorCode $errorText $failedUrl")
            }
        }
    })
    val browser = client.createBrowser(url, false, false)

    SwingUtilities.invokeAndWait {
        frame = JFrame(WINDOW_TITLE)
        frame.contentPane.add(browser.uiComponent, BorderLayout.CENTER)
        frame.setSize(WINDOW_WIDTH, WINDOW_HEIGHT)
        frame.setLocationRelativeTo(null)
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                server.stop()
                smoke.finished()
            }
        })
        frame.isVisible = true
    }
}

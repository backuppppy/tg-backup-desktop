package org.tgbackup.desktop

import com.sun.jna.Pointer
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

const val TRAY_TOOLTIP = "TG Backup — רץ ברקע"
const val TRAY_OPEN = "פתח"
const val TRAY_QUIT = "יציאה"

/** DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2. */
private const val DPI_PER_MONITOR_V2 = -4L

/**
 * The icon by the clock while the program runs in the background (AWT's SystemTray). Every AWT call runs on the
 * EDT; [showIcon] and [hideIcon] wait for it, so the icon is gone before the program goes on to shut down.
 */
class Tray private constructor() : TrayControl {
    /** EDT only. */
    private var icon: TrayIcon? = null

    override fun showIcon(onOpen: () -> Unit, onQuit: () -> Unit, notice: Pair<String, String>?) = onEdt {
        if (icon != null) return@onEdt
        val tray = SystemTray.getSystemTray()
        val menu = PopupMenu().apply {
            add(MenuItem(TRAY_OPEN).apply { addActionListener { onOpen() } })
            add(MenuItem(TRAY_QUIT).apply { addActionListener { onQuit() } })
        }
        val t = TrayIcon(trayImage(tray.trayIconSize.width), TRAY_TOOLTIP, menu).apply {
            isImageAutoSize = true
            // Left click opens; the right button shows the menu (AWT does that itself).
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) onOpen()
                }
            })
        }
        tray.add(t)
        icon = t
        notice?.let { (title, text) -> t.displayMessage(title, text, TrayIcon.MessageType.INFO) }
    }

    override fun hideIcon() = onEdt {
        icon?.let { SystemTray.getSystemTray().remove(it) }
        icon = null
    }

    private fun onEdt(block: () -> Unit) {
        if (EventQueue.isDispatchThread()) block() else EventQueue.invokeAndWait(block)
    }

    companion object {
        /**
         * The tray, or null when this system has none (then closing the window always ends the program).
         * This loads AWT, which claims a DPI mode for the whole process; WebView2 (webview.dll) asks for
         * per-monitor v2, so that is claimed first and the window looks the same whichever comes first.
         */
        fun createOrNull(): Tray? {
            runCatching { User32More.INSTANCE.SetProcessDpiAwarenessContext(Pointer.createConstant(DPI_PER_MONITOR_V2)) }
            val supported = runCatching { !GraphicsEnvironment.isHeadless() && SystemTray.isSupported() }
                .getOrDefault(false)
            if (!supported) println("TRAY_UNSUPPORTED")
            return if (supported) Tray() else null
        }
    }
}

/**
 * The user's cloud in a black circle for [px] (the tray's icon size, [SystemTray.getTrayIconSize]): `tray-16.png`
 * up to 16 px, `tray-32.png` above (AWT scales it to the tray, isImageAutoSize). Both are made by
 * tools/make-icons.py and always packaged.
 */
internal fun trayImage(px: Int): BufferedImage {
    val name = if (px <= 16) "tray-16.png" else "tray-32.png"
    val stream = checkNotNull(Tray::class.java.getResourceAsStream("/$name")) { "$name missing from the resources" }
    return checkNotNull(stream.use(ImageIO::read)) { "$name is not an image" }
}

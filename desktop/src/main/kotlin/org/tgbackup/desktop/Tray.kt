package org.tgbackup.desktop

import com.sun.jna.Pointer
import java.awt.Color
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
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

    override fun showIcon(onOpen: () -> Unit, onQuit: () -> Unit) = onEdt {
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
 * The icon for [px] (the tray's icon size): `tray-16.png` / `tray-32.png` from the resources once Task 6 adds
 * them, until then [placeholderTrayImage].
 */
internal fun trayImage(px: Int): Image {
    val name = if (px <= 16) "tray-16.png" else "tray-32.png"
    val image = Tray::class.java.getResourceAsStream("/$name")?.use { runCatching { ImageIO.read(it) }.getOrNull() }
    return image ?: placeholderTrayImage(px)
}

/** Placeholder until Task 6: a white circle on a black circle, transparent corners. */
internal fun placeholderTrayImage(px: Int): BufferedImage {
    val size = px.coerceAtLeast(16)
    return BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
        val g = createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.BLACK
        g.fillOval(0, 0, size, size)
        g.color = Color.WHITE
        val inset = size / 4
        g.fillOval(inset, inset, size - 2 * inset, size - 2 * inset)
        g.dispose()
    }
}

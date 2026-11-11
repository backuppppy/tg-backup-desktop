package org.tgbackup.desktop

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IconsTest {
    @Test
    fun trayPngsLoadFromTheResourcesAtTheirSizes() {
        for (px in listOf(16, 32)) {
            val stream = assertNotNull(IconsTest::class.java.getResourceAsStream("/tray-$px.png"), "tray-$px.png")
            val image = assertNotNull(stream.use { ImageIO.read(it) })
            assertEquals(px, image.width)
            assertEquals(px, image.height)
            // A black circle with transparent corners.
            assertEquals(0, image.getRGB(0, 0) ushr 24, "corner of tray-$px.png is transparent")
        }
    }

    @Test
    fun trayImageIsTheResourceForTheTraySize() {
        assertEquals(16, trayImage(16).getWidth(null))
        assertEquals(32, trayImage(24).getWidth(null))
        assertEquals(32, trayImage(32).getWidth(null))
        assertEquals(32, trayImage(48).getWidth(null))
    }

    @Test
    fun programIconHasTheSevenSizes() {
        assertEquals(listOf(16, 24, 32, 48, 64, 128, 256), icoFrames(File("src/main/icons/TGBackup.ico")).keys.sorted())
    }

    @Test
    fun programIcoIsUnchanged() {
        // The BACKUP logo stays the program's icon (exe, shortcuts, Add/Remove Programs); only the window's changed.
        val bytes = File("src/main/icons/TGBackup.ico").readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("0c1a3440684b9d73d444ac4b1e12d307f115b24f2a1dde709709592e1358746e", sha)
    }

    @Test
    fun windowIcoHasTheCloudFrames() {
        val frames = icoFrames(File("src/main/icons/$WINDOW_ICON_FILE"))
        assertEquals(listOf(16, 20, 24, 32, 40, 48, 64, 256), frames.keys.sorted())
        val frame32 = assertNotNull(ImageIO.read(ByteArrayInputStream(frames.getValue(32))), "32 px frame")
        // The cloud drawing at 32 px is tray-32.png (the tray icon); the logo's 32 px frame is the program icon's.
        val cloud = assertNotNull(IconsTest::class.java.getResourceAsStream("/tray-32.png")).use { ImageIO.read(it) }
        val logo = ImageIO.read(ByteArrayInputStream(icoFrames(File("src/main/icons/TGBackup.ico")).getValue(32)))
        val toCloud = meanDiff(frame32, cloud)
        val toLogo = meanDiff(frame32, logo)
        assertTrue(toCloud <= 2.0, "32 px frame differs from the cloud drawing by $toCloud")
        assertTrue(toLogo > 10.0, "32 px frame is too close to the logo ($toLogo)")
    }

    /** The frames of an .ico by their pixel size (0 in the 1-byte width meaning 256), as stored (PNG here). */
    private fun icoFrames(ico: File): Map<Int, ByteArray> {
        assertTrue(ico.isFile, "${ico.absolutePath} missing")
        val bytes = ico.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, b.getShort(0).toInt(), "ICONDIR reserved")
        assertEquals(1, b.getShort(2).toInt(), "ICONDIR type (1 = icon)")
        val count = b.getShort(4).toInt()
        // ICONDIRENTRY (16 bytes each from offset 6): width, height, ..., bytes in resource at 8, offset at 12.
        return (0 until count).associate { i ->
            val e = 6 + 16 * i
            val w = b.get(e).toInt() and 0xff
            val h = b.get(e + 1).toInt() and 0xff
            assertEquals(w, h)
            val size = b.getInt(e + 8)
            val offset = b.getInt(e + 12)
            (if (w == 0) 256 else w) to bytes.copyOfRange(offset, offset + size)
        }
    }

    /** Mean absolute difference per channel (ARGB, 0..255) of two images of the same size. */
    private fun meanDiff(a: BufferedImage, b: BufferedImage): Double {
        assertEquals(a.width to a.height, b.width to b.height)
        var sum = 0L
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getRGB(x, y)
            val q = b.getRGB(x, y)
            for (shift in listOf(24, 16, 8, 0)) sum += kotlin.math.abs((p ushr shift and 0xff) - (q ushr shift and 0xff))
        }
        return sum.toDouble() / (a.width * a.height * 4)
    }
}

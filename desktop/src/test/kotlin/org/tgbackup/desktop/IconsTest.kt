package org.tgbackup.desktop

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        val ico = File("src/main/icons/TGBackup.ico")
        assertTrue(ico.isFile, "${ico.absolutePath} missing")
        val b = ByteBuffer.wrap(ico.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, b.getShort(0).toInt(), "ICONDIR reserved")
        assertEquals(1, b.getShort(2).toInt(), "ICONDIR type (1 = icon)")
        val count = b.getShort(4).toInt()
        // ICONDIRENTRY (16 bytes each from offset 6): width and height bytes, 0 meaning 256.
        val sizes = (0 until count).map { i ->
            val w = b.get(6 + 16 * i).toInt() and 0xff
            val h = b.get(7 + 16 * i).toInt() and 0xff
            assertEquals(w, h)
            if (w == 0) 256 else w
        }.sorted()
        assertEquals(listOf(16, 24, 32, 48, 64, 128, 256), sizes)
    }
}

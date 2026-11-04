package org.tgbackup.app.export

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmImageThumbsTest {
    private val dir = File(kotlin.io.path.createTempDirectory("thumbs").toFile(), "תיקייה עם רווח").also { it.mkdirs() }
    private val thumbs = JvmImageThumbs()

    @AfterTest
    fun cleanup() {
        dir.parentFile.deleteRecursively()
    }

    private fun png(name: String, w: Int, h: Int): File {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until w) for (y in 0 until h) img.setRGB(x, y, (0x80 shl 24) or (x * 255 / w shl 16) or (y * 255 / h shl 8))
        return File(dir, name).also { ImageIO.write(img, "png", it) }
    }

    @Test
    fun sizeReadsDimensions() {
        assertEquals(400 to 300, thumbs.size(png("a.png", 400, 300).path))
    }

    @Test
    fun sizeOfABrokenFileIsNull() {
        val f = File(dir, "bad.png").also { it.writeText("not an image") }
        assertNull(thumbs.size(f.path))
    }

    @Test
    fun scalesPngToJpegWithExactSize() {
        val src = png("a.png", 400, 300)
        val target = File(dir, "sub/t.jpg")
        assertTrue(thumbs.scaleAndSave(src.path, 100, 75, target.path, "jpg", -1))
        val out = assertNotNull(ImageIO.read(target))
        assertEquals(100, out.width)
        assertEquals(75, out.height)
    }

    @Test
    fun keepsPngFormatWhenFormatIsNull() {
        val src = png("a.png", 200, 100)
        val target = File(dir, "t.out")
        assertTrue(thumbs.scaleAndSave(src.path, 50, 50, target.path, null, -1))
        val readers = ImageIO.createImageInputStream(target).use { ImageIO.getImageReaders(it).next().formatName }
        assertEquals("png", readers.lowercase())
        val out = assertNotNull(ImageIO.read(target))
        assertEquals(50, out.width)
        assertEquals(50, out.height)
    }

    @Test
    fun webpIsNotSupported() {
        val src = png("a.png", 100, 100)
        val target = File(dir, "t.webp")
        assertFalse(thumbs.scaleAndSave(src.path, 50, 50, target.path, "webp", -1))
        assertFalse(target.exists())
    }

    @Test
    fun brokenSourceReturnsFalse() {
        val f = File(dir, "bad.png").also { it.writeText("not an image") }
        assertFalse(thumbs.scaleAndSave(f.path, 10, 10, File(dir, "x.png").path, "png", -1))
    }
}

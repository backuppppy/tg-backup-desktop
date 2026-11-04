package org.tgbackup.app.export

import org.tgbackup.export.output.ImageThumbs
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Desktop counterpart of AndroidImageThumbs, on Java ImageIO. The default JPEG
 * quality of Qt is 75. ImageIO cannot write WebP, so that format returns false.
 */
class JvmImageThumbs : ImageThumbs {
    init {
        // No ImageIO cache files under java.io.tmpdir.
        ImageIO.setUseCache(false)
    }

    override fun size(path: String): Pair<Int, Int>? = try {
        withReader(path) { reader, _ ->
            val w = reader.getWidth(0)
            val h = reader.getHeight(0)
            if (w <= 0 || h <= 0) null else w to h
        }
    } catch (e: Exception) {
        null
    }

    override fun scaleAndSave(path: String, width: Int, height: Int, target: String, format: String?, quality: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        return try {
            val sourceFormat = withReader(path) { reader, _ -> reader.formatName } ?: return false
            val fmt = when ((format ?: sourceFormat).lowercase()) {
                "png", "image/png" -> "png"
                "jpg", "jpeg", "image/jpeg" -> "jpg"
                else -> return false
            }
            val source = decodeSubsampled(path, width, height) ?: return false
            var scaled = scale(source, width, height, argb = fmt == "png")
            if (fmt == "jpg") scaled = flattenOnWhite(scaled)
            val out = File(target)
            out.parentFile?.mkdirs()
            val ok = try {
                if (fmt == "jpg") writeJpeg(scaled, out, if (quality < 0) 75 else quality) else ImageIO.write(scaled, "png", out)
            } catch (e: Exception) {
                false
            }
            if (!ok) out.delete()
            ok
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            File(target).delete()
            false
        }
    }

    /** Decodes with the largest power-of-two subsampling that keeps the image at least the target size. */
    private fun decodeSubsampled(path: String, width: Int, height: Int): BufferedImage? =
        withReader(path) { reader, _ ->
            val w = reader.getWidth(0)
            val h = reader.getHeight(0)
            var s = 1
            while (w / (s * 2) >= width && h / (s * 2) >= height) s *= 2
            val param = reader.defaultReadParam.apply { setSourceSubsampling(s, s, 0, 0) }
            reader.read(0, param)
        }

    private fun <T> withReader(path: String, block: (javax.imageio.ImageReader, Unit) -> T): T? {
        ImageIO.createImageInputStream(File(path)).use { stream ->
            if (stream == null) return null
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            return try {
                reader.setInput(stream, true, true)
                block(reader, Unit)
            } finally {
                reader.dispose()
            }
        }
    }

    /** Progressive halving while still at least twice the target, then bicubic to the exact size. */
    private fun scale(src: BufferedImage, width: Int, height: Int, argb: Boolean): BufferedImage {
        val type = if (argb) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        var cur = src
        while (cur.width / 2 >= width && cur.height / 2 >= height) {
            cur = draw(cur, cur.width / 2, cur.height / 2, type)
        }
        return draw(cur, width, height, type)
    }

    private fun draw(src: BufferedImage, w: Int, h: Int, type: Int): BufferedImage {
        val dst = BufferedImage(w, h, type)
        val g = dst.createGraphics()
        try {
            if (!dst.colorModel.hasAlpha()) {
                g.color = Color.WHITE
                g.fillRect(0, 0, w, h)
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(src, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return dst
    }

    private fun flattenOnWhite(img: BufferedImage): BufferedImage =
        if (img.type == BufferedImage.TYPE_INT_RGB) img else draw(img, img.width, img.height, BufferedImage.TYPE_INT_RGB)

    private fun writeJpeg(img: BufferedImage, out: File, quality: Int): Boolean {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").takeIf { it.hasNext() }?.next() ?: return false
        return try {
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality.coerceIn(0, 100) / 100f
            }
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(img, null, null), param)
            }
            true
        } finally {
            writer.dispose()
        }
    }
}

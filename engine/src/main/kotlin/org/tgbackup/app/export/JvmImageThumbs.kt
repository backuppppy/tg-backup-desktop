package org.tgbackup.app.export

import org.tgbackup.export.output.ImageThumbs

/** Desktop replacement for AndroidImageThumbs. Stub for now; implemented with ImageIO in Task 3. */
class JvmImageThumbs : ImageThumbs {
    override fun size(path: String): Pair<Int, Int>? = TODO("JvmImageThumbs.size: Task 3")

    override fun scaleAndSave(
        path: String,
        width: Int,
        height: Int,
        target: String,
        format: String?,
        quality: Int,
    ): Boolean = TODO("JvmImageThumbs.scaleAndSave: Task 3")
}

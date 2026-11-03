package org.tgbackup.desktop

/** A screen rectangle in Win32 terms: [right] and [bottom] are exclusive. */
data class ScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun isInside(other: ScreenRect): Boolean =
        left >= other.left && top >= other.top && right <= other.right && bottom <= other.bottom
}

object WindowFit {
    /**
     * Places a [width]×[height] window in the centre of [work] (the monitor's work area, without the
     * taskbar). A side larger than the work area is shrunk to it, so the window is never clipped.
     */
    fun fit(work: ScreenRect, width: Int, height: Int): ScreenRect {
        val w = minOf(width, work.width)
        val h = minOf(height, work.height)
        val left = work.left + (work.width - w) / 2
        val top = work.top + (work.height - h) / 2
        return ScreenRect(left, top, left + w, top + h)
    }
}

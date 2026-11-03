package org.tgbackup.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowFitTest {
    @Test
    fun windowThatFitsIsCentredInTheWorkArea() {
        val r = WindowFit.fit(work = ScreenRect(0, 0, 1920, 1040), width = 1100, height = 750)
        assertEquals(ScreenRect(410, 145, 1510, 895), r)
    }

    @Test
    fun windowWiderAndTallerThanTheScreenIsShrunkToTheWorkArea() {
        // The CI runner: 1024x768 with a 48 px taskbar at the bottom.
        val work = ScreenRect(0, 0, 1024, 720)
        val r = WindowFit.fit(work, width = 1100, height = 750)
        assertEquals(work, r)
    }

    @Test
    fun onlyTheTooLargeSideIsShrunk() {
        val r = WindowFit.fit(work = ScreenRect(0, 0, 1024, 1000), width = 1100, height = 750)
        assertEquals(ScreenRect(0, 125, 1024, 875), r)
    }

    @Test
    fun workAreaWithOffsetIsRespected() {
        // A second monitor to the left (negative x) with the taskbar at the top.
        val work = ScreenRect(-1280, 40, 0, 1024)
        val r = WindowFit.fit(work, width = 1100, height = 750)
        assertEquals(ScreenRect(-1190, 157, -90, 907), r)
        assertTrue(r.isInside(work))
    }

    @Test
    fun isInsideDetectsAClippedWindow() {
        val work = ScreenRect(0, 0, 1024, 720)
        assertTrue(ScreenRect(0, 0, 1024, 720).isInside(work))
        assertTrue(!ScreenRect(86, 0, 1186, 750).isInside(work))
    }
}

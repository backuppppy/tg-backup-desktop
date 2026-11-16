package org.tgbackup.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsScreenTest {
    /** QUERY_USER_NOTIFICATION_STATE: 1 not present, 2 busy, 3 D3D full screen, 4 presentation, 5 accepts notifications (normal), 6 quiet time, 7 app. */
    @Test
    fun onlyFullScreenAndPresentationCountAsPresenting() {
        assertEquals(listOf(2, 3, 4), (1..7).filter { isPresentingState(it) })
    }
}

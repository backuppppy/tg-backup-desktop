package org.tgbackup.copy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SplitTextTest {
    @Test
    fun shortStaysWhole() {
        assertEquals("abc" to null, splitText("abc", 10))
    }

    @Test
    fun cutsAtWhitespace() {
        assertEquals("aaaa bbbb" to "cccc dddd", splitText("aaaa bbbb cccc dddd", 12))
    }

    @Test
    fun neverSplitsASurrogatePair() {
        assertEquals("abc" to "😀def", splitText("abc😀def", 4))
    }

    @Test
    fun partsCoverEverything() {
        val text = (1..60).joinToString(" ") { "w$it" }
        val parts = splitTextAll(text, 30)
        assertEquals(text.split(" "), parts.flatMap { it.split(" ") })
        assertNull(parts.firstOrNull { it.length > 30 })
    }
}

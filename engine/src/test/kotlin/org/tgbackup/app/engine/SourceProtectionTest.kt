package org.tgbackup.app.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceProtectionTest {
    @Test
    fun startsWithInitial() {
        assertTrue(SourceProtection(true).isProtected)
        assertFalse(SourceProtection(false).isProtected)
    }

    @Test
    fun removedThenAdded() {
        val p = SourceProtection(true)
        assertTrue(p.onNotice(chatId = 5, sourceId = 5, value = false))
        assertFalse(p.isProtected)
        assertTrue(p.onNotice(chatId = 5, sourceId = 5, value = true))
        assertTrue(p.isProtected)
    }

    @Test
    fun ignoresOtherChats() {
        val p = SourceProtection(true)
        assertFalse(p.onNotice(chatId = 6, sourceId = 5, value = false))
        assertTrue(p.isProtected)
    }

    @Test
    fun sameValueIsNoChange() {
        val p = SourceProtection(true)
        assertFalse(p.set(true))
        assertEquals(true, p.isProtected)
        assertFalse(p.onNotice(chatId = 5, sourceId = 5, value = true))
    }
}

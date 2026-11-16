package org.tgbackup.app.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SliceQueueTest {
    @Test
    fun allDoneReturnsNull() = runBlocking {
        val q = SliceQueue(2)
        assertFalse(q.allDone)
        q.done(0)
        q.done(1)
        assertTrue(q.allDone)
        assertNull(withTimeout(1_000) { q.next() })
    }

    @Test
    fun releasedIsTaken() = runBlocking {
        val q = SliceQueue(2)
        q.done(0)
        q.release(1)
        assertEquals(1, withTimeout(1_000) { q.next() })
        q.done(1)
        assertNull(withTimeout(1_000) { q.next() })
    }

    @Test
    fun waitsWhileHeld() = runBlocking {
        val q = SliceQueue(2)
        q.done(0)
        val waiter = async { q.next() }
        delay(100)
        assertTrue(waiter.isActive, "next() waits while slice 1 is still held")
        q.release(1)
        assertEquals(1, withTimeout(1_000) { waiter.await() })
    }

    @Test
    fun neverTwoHolders() = runBlocking {
        val q = SliceQueue(1)
        q.release(0)
        val a = async { q.next() }
        val b = async { q.next() }
        delay(100)
        // Exactly one of them holds slice 0; the other waits until it is done.
        val first = if (a.isCompleted) a else b
        val second = if (a.isCompleted) b else a
        assertEquals(0, first.await())
        assertTrue(second.isActive)
        q.done(0)
        assertNull(withTimeout(1_000) { second.await() })
    }
}

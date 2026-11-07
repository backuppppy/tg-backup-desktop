package org.tgbackup.app.export

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PasswordQueueTest {

    @Test
    fun aJobsRequestAndTheScreensRequestAreBothListedAndNeitherOverwritesTheOther() = runBlocking {
        val queue = PasswordQueue()
        val job = async(start = CoroutineStart.UNDISPATCHED) { queue.ask("a1", PasswordOrigin.Job, "hint-job") }
        val screen = async(start = CoroutineStart.UNDISPATCHED) { queue.ask("a2", PasswordOrigin.Screen, "hint-screen") }
        val listed = queue.all.value
        assertEquals(listOf("a1" to PasswordOrigin.Job, "a2" to PasswordOrigin.Screen), listed.map { it.accountId to it.origin })
        assertEquals(listOf("hint-job", "hint-screen"), listed.map { it.hint })
        // `first` (ExportManager.password) is the oldest request, as before.
        assertSame(listed[0], queue.first.value)

        // Answering the screen's request reaches the screen's login only.
        assertTrue(listed[1].answer.complete("screen-pw"))
        assertEquals("screen-pw", screen.await())
        assertFalse(job.isCompleted)
        assertEquals(listOf(listed[0]), queue.all.value)
        assertSame(listed[0], queue.first.value)

        assertTrue(listed[0].answer.complete("job-pw"))
        assertEquals("job-pw", job.await())
        assertTrue(queue.all.value.isEmpty())
        assertNull(queue.first.value)
    }

    @Test
    fun aCancelledWaitLeavesTheList() = runBlocking {
        val queue = PasswordQueue()
        val first = async(start = CoroutineStart.UNDISPATCHED) { queue.ask("a1", PasswordOrigin.Screen, "") }
        val second = async(start = CoroutineStart.UNDISPATCHED) { queue.ask("a1", PasswordOrigin.Job, "") }
        val (r1, r2) = queue.all.value
        first.cancel()
        first.join()
        assertEquals(listOf(r2), queue.all.value)
        assertSame(r2, queue.first.value)
        // A null answer (the dialog's cancel) is handed back as null.
        r2.answer.complete(null)
        assertNull(second.await())
        assertTrue(queue.all.value.isEmpty())
        assertFalse(r1.answer.isCompleted)
    }

    @Test
    fun aRequestDefaultsToTheScreen() {
        val r = PasswordRequest("h", kotlinx.coroutines.CompletableDeferred())
        assertEquals(PasswordOrigin.Screen, r.origin)
        assertEquals("", r.accountId)
    }
}

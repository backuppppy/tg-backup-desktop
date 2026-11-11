package org.tgbackup.app.export

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.tgbackup.app.engine.LogBus
import org.tgbackup.app.tg.AccountManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The export screen and a copying job log the same account in one at a time; other accounts do not wait. */
class LoginLockTest {
    private fun manager(): ExportManager {
        val dir = kotlin.io.path.createTempDirectory("login-lock").toFile()
        val log = LogBus(dir)
        return ExportManager(dir, AccountManager(dir, log), log)
    }

    @Test
    fun sameAccountLoginsRunOneAtATime() = runBlocking {
        val m = manager()
        val firstIn = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondIn = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            m.serialLogin("a1") { firstIn.complete(Unit); releaseFirst.await(); "first" }
        }
        withTimeout(5_000) { firstIn.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            m.serialLogin("a1") { secondIn.complete(Unit); "second" }
        }
        repeat(20) { yield() }
        assertFalse(secondIn.isCompleted, "the second login of a1 started while the first still ran")
        releaseFirst.complete(Unit)
        assertEquals("first", withTimeout(5_000) { first.await() })
        assertEquals("second", withTimeout(5_000) { second.await() })
        assertTrue(secondIn.isCompleted)
    }

    @Test
    fun otherAccountsLoginInParallel() = runBlocking {
        val m = manager()
        val firstIn = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            m.serialLogin("a1") { firstIn.complete(Unit); releaseFirst.await(); "a1" }
        }
        withTimeout(5_000) { firstIn.await() }
        // a2 runs to the end while a1 is still inside its login.
        assertEquals("a2", withTimeout(5_000) { m.serialLogin("a2") { "a2" } })
        assertFalse(first.isCompleted)
        releaseFirst.complete(Unit)
        assertEquals("a1", withTimeout(5_000) { first.await() })
    }

    @Test
    fun aRequestIsNotWrongByDefaultAndTheQueueCarriesWrong() = runBlocking {
        assertFalse(PasswordRequest("h", CompletableDeferred()).wrong)
        val queue = PasswordQueue()
        val asked = async(start = CoroutineStart.UNDISPATCHED) { queue.ask("a1", PasswordOrigin.Screen, "h", wrong = true) }
        val r = queue.all.value.single()
        assertTrue(r.wrong)
        r.answer.complete("pw")
        assertEquals("pw", asked.await())
    }
}

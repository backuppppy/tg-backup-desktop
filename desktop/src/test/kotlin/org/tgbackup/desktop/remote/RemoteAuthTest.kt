package org.tgbackup.desktop.remote

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RemoteAuthTest {
    private fun dir(): File = Files.createTempDirectory("remote-auth").toFile()

    private class Clock(var now: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    private fun RemoteAuth.session(pw: String = "secret1", who: String = "phone"): String =
        (login(pw, who) as LoginResult.Ok).session

    @Test
    fun aShortPasswordIsRefused() {
        val auth = RemoteAuth(dir())
        assertFailsWith<IllegalArgumentException> { auth.setPassword("12345") }
        assertFalse(auth.hasPassword())
    }

    @Test
    fun theRightPasswordGivesASessionThatValidates() {
        val auth = RemoteAuth(dir())
        auth.setPassword("secret1")
        assertTrue(auth.hasPassword())
        val s = auth.session()
        assertTrue(auth.validate(s))
        assertFalse(auth.validate("nope"))
        assertFalse(auth.validate(null))
        assertEquals(1, auth.devices.value)
    }

    @Test
    fun theWrongPasswordIsWrong() {
        val auth = RemoteAuth(dir())
        auth.setPassword("secret1")
        assertEquals(LoginResult.Wrong, auth.login("secret2", "phone"))
        assertEquals(0, auth.devices.value)
    }

    @Test
    fun sessionsSurviveANewInstance() {
        val d = dir()
        val auth = RemoteAuth(d)
        auth.setPassword("secret1")
        val s = auth.session()
        val again = RemoteAuth(d)
        assertTrue(again.hasPassword())
        assertTrue(again.validate(s))
        assertEquals(1, again.devices.value)
        assertIs<LoginResult.Ok>(again.login("secret1", "phone"))
    }

    @Test
    fun theFileHoldsNoPasswordOrSession() {
        val d = dir()
        val auth = RemoteAuth(d)
        auth.setPassword("secret-pass-1")
        val s = auth.session("secret-pass-1")
        val text = File(d, "remote.json").readText()
        assertFalse(text.contains("secret-pass-1"))
        assertFalse(text.contains(s))
        assertTrue(text.contains("\"iterations\""))
        assertEquals(listOf("remote.json"), d.list()!!.sorted())
    }

    @Test
    fun changingThePasswordDropsAllSessions() {
        val auth = RemoteAuth(dir())
        auth.setPassword("secret1")
        val s = auth.session()
        auth.setPassword("another2")
        assertFalse(auth.validate(s))
        assertEquals(0, auth.devices.value)
        assertEquals(LoginResult.Wrong, auth.login("secret1", "phone"))
        assertIs<LoginResult.Ok>(auth.login("another2", "phone"))
    }

    @Test
    fun logoutDropsOnlyThatSession() {
        val auth = RemoteAuth(dir())
        auth.setPassword("secret1")
        val a = auth.session()
        val b = auth.session()
        auth.logout(a)
        assertFalse(auth.validate(a))
        assertTrue(auth.validate(b))
        assertEquals(1, auth.devices.value)
    }

    @Test
    fun disconnectAllDropsAll() {
        val d = dir()
        val auth = RemoteAuth(d)
        auth.setPassword("secret1")
        val a = auth.session()
        val b = auth.session()
        assertEquals(2, auth.devices.value)
        auth.disconnectAll()
        assertEquals(0, auth.devices.value)
        assertFalse(auth.validate(a))
        assertFalse(auth.validate(b))
        assertEquals(0, RemoteAuth(d).devices.value)
        assertTrue(RemoteAuth(d).hasPassword())
    }

    @Test
    fun theFourthFailureWaits() {
        val clock = Clock()
        val auth = RemoteAuth(dir(), clock)
        auth.setPassword("secret1")
        repeat(3) { assertEquals(LoginResult.Wrong, auth.login("bad-pass", "phone")) }
        assertEquals(LoginResult.Wait(2), auth.login("bad-pass", "phone"))
        // While waiting, even the right password is not checked.
        assertEquals(LoginResult.Wait(2), auth.login("secret1", "phone"))
        clock.now += 2_000
        assertEquals(LoginResult.Wrong, auth.login("bad-pass", "phone"))
        assertEquals(LoginResult.Wait(4), auth.login("bad-pass", "phone"))
        // Another device is not held back by this one.
        assertIs<LoginResult.Ok>(auth.login("secret1", "tablet"))
        // A success resets that device.
        clock.now += 4_000
        assertIs<LoginResult.Ok>(auth.login("secret1", "phone"))
        assertEquals(LoginResult.Wrong, auth.login("bad-pass", "phone"))
    }

    @Test
    fun theWaitIsCappedAtFiveMinutes() {
        val clock = Clock()
        val auth = RemoteAuth(dir(), clock)
        auth.setPassword("secret1")
        var last = 0
        repeat(12) {
            clock.now += 400_000
            auth.login("bad-pass", "phone")
            last = (auth.login("bad-pass", "phone") as? LoginResult.Wait)?.seconds ?: last
        }
        assertEquals(300, last)
    }

    @Test
    fun twentyFailuresLockEveryone() {
        val clock = Clock()
        val auth = RemoteAuth(dir(), clock)
        auth.setPassword("secret1")
        repeat(20) { assertEquals(LoginResult.Wrong, auth.login("bad-pass", "who-$it")) }
        assertEquals(LoginResult.Wait(60), auth.login("secret1", "someone-new"))
        clock.now += 60_000
        assertIs<LoginResult.Ok>(auth.login("secret1", "someone-new"))
    }

    @Test
    fun aBadFileMeansNoPassword() {
        val d = dir()
        File(d, "remote.json").writeText("{not json")
        val auth = RemoteAuth(d)
        assertFalse(auth.hasPassword())
        assertEquals(0, auth.devices.value)
        assertFalse(auth.validate("anything"))
        assertEquals(LoginResult.Wrong, auth.login("secret1", "phone"))
    }
    @Test
    fun failedCallersAreCappedAndAWaitAddsNone() {
        val clock = Clock()
        // No password: every login is Wrong at once (no PBKDF2), so many callers stay fast.
        val auth = RemoteAuth(dir(), clock)
        repeat(20) { assertEquals(LoginResult.Wrong, auth.login("bad-pass", "who-$it")) }
        assertEquals(20, auth.trackedCallers())
        // During the global lock nothing is counted, so new names add nothing.
        repeat(50) { assertIs<LoginResult.Wait>(auth.login("bad-pass", "new-$it")) }
        assertEquals(20, auth.trackedCallers())
        // A minute apart, no global lock: each name is one failure; the map stops at 1000.
        repeat(1_100) {
            clock.now += 60_001
            assertEquals(LoginResult.Wrong, auth.login("bad-pass", "many-$it"))
        }
        assertEquals(1_000, auth.trackedCallers())
    }

    @Test
    fun validateIsTrueWhenTheLastSeenSaveFails() {
        val clock = Clock()
        val d = dir()
        val auth = RemoteAuth(d, clock)
        auth.setPassword("secret1")
        val s = auth.session()
        // A directory where the temp file goes: the save throws (as on a full disk or a locked file).
        File(d, "remote.json.tmp").mkdir()
        clock.now += 61_000
        assertTrue(auth.validate(s))
        assertEquals(1, auth.devices.value)
    }
    @Test
    fun aFailedLastSeenSaveIsNotRetriedOnEveryCall() {
        val clock = Clock()
        val d = dir()
        val auth = RemoteAuth(d, clock)
        auth.setPassword("secret1")
        val s = auth.session()
        val before = File(d, "remote.json").readText()
        val blocker = File(d, "remote.json.tmp").also { it.mkdir() }
        clock.now += 61_000
        assertTrue(auth.validate(s))
        // The disk is fine again, but the last-seen time in memory is new: no write for another minute.
        blocker.delete()
        clock.now += 1_000
        assertTrue(auth.validate(s))
        assertEquals(before, File(d, "remote.json").readText())
    }

    @Test
    fun validateDoesNotWaitForALogin() {
        val auth = RemoteAuth(dir())
        auth.setPassword("secret1")
        val s = auth.session()
        // One login's PBKDF2 time.
        val t0 = System.nanoTime()
        auth.login("bad-pass", "warmup")
        val loginNanos = System.nanoTime() - t0
        val logins = Thread { repeat(6) { auth.login("secret1", "phone") } }.apply { start() }
        val waits = mutableListOf<Long>()
        while (logins.isAlive && waits.size < 40) {
            val v0 = System.nanoTime()
            assertTrue(auth.validate(s))
            waits += System.nanoTime() - v0
            Thread.sleep(loginNanos / 4_000_000 + 1)
        }
        logins.join()
        val median = waits.sorted()[waits.size / 2]
        assertTrue(median < loginNanos / 4, "median validate ${median / 1_000_000} ms, login ${loginNanos / 1_000_000} ms")
    }
}

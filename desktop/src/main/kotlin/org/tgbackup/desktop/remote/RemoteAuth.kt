package org.tgbackup.desktop.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tgbackup.io.AtomicFiles
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

sealed interface LoginResult {
    data class Ok(val session: String) : LoginResult
    data object Wrong : LoginResult
    data class Wait(val seconds: Int) : LoginResult
}

@Serializable
private data class StoredSession(val sha256: String, val created: Long, val lastSeen: Long)

@Serializable
private data class RemoteData(
    val hash: String = "",
    val salt: String = "",
    val iterations: Int = 0,
    val sessions: List<StoredSession> = emptyList(),
)

/**
 * The phone-access login: one password (PBKDF2, never stored as text), sessions (random values, only their SHA-256 is
 * stored) and a growing wait after failed logins. Everything lives in `remote.json` under [dir]; a missing or
 * unreadable file means "no password". The wait state is memory only.
 */
class RemoteAuth(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, FILE_NAME)
    private var data: RemoteData = load()
    private val count = MutableStateFlow(data.sessions.size)

    private class Failures(var count: Int = 0, var blockedUntil: Long = 0)

    /** Insertion order: the oldest entry is the first. */
    private val perWho = LinkedHashMap<String, Failures>()
    private val recentFailures = ArrayDeque<Long>()
    private var lockedUntil = 0L

    /** Number of signed-in devices. */
    val devices: StateFlow<Int> = count.asStateFlow()

    private val ended = MutableStateFlow(0L)

    /** Bumped whenever sessions end (logout, disconnect all, a new password): open streams re-check theirs. */
    internal val sessionsEnded: StateFlow<Long> = ended.asStateFlow()

    @Synchronized
    fun hasPassword(): Boolean = data.hash.isNotEmpty()

    /** Sets the password (at least 6 characters) and drops every session. */
    @Synchronized
    fun setPassword(pw: String) {
        require(pw.length >= MIN_PASSWORD) { "password too short" }
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        save(RemoteData(hash = hex(derive(pw, salt, ITERATIONS)), salt = hex(salt), iterations = ITERATIONS))
        ended.value++
    }

    /**
     * The waits are checked first, under the lock; PBKDF2 then runs outside it (so [validate] never waits for a
     * login), and the result is recorded under the lock again. A password changed meanwhile makes it Wrong.
     */
    fun login(pw: String, who: String): LoginResult {
        val stored = synchronized(this) {
            val now = clock()
            // `who` comes from the client's headers: a caller gets an entry only on a failure, and the map is capped.
            if (lockedUntil > now) return LoginResult.Wait(secondsUntil(lockedUntil, now))
            val known = perWho[who]
            if (known != null && known.blockedUntil > now) return LoginResult.Wait(secondsUntil(known.blockedUntil, now))
            data
        }
        val matches = passwordMatches(pw, stored)
        synchronized(this) {
            val now = clock()
            if (!matches || data.hash != stored.hash) {
                fail(perWho[who] ?: track(who), now)
                return LoginResult.Wrong
            }
            perWho.remove(who)
            val session = hex(ByteArray(SESSION_BYTES).also { random.nextBytes(it) })
            save(data.copy(sessions = data.sessions + StoredSession(sha256(session), now, now)))
            return LoginResult.Ok(session)
        }
    }

    /** True for a live session; refreshes its last-seen time at most once a minute. */
    @Synchronized
    fun validate(session: String?): Boolean {
        if (session.isNullOrEmpty()) return false
        val hash = sha256(session)
        val found = data.sessions.firstOrNull { it.sha256 == hash } ?: return false
        val now = clock()
        if (now - found.lastSeen >= SEEN_EVERY_MS) {
            // Best effort: a failed save (a full disk, a locked file) must not refuse a live session. The new time is
            // kept in memory either way, so the next calls do not try to write again.
            val next = data.copy(sessions = data.sessions.map { if (it === found) it.copy(lastSeen = now) else it })
            try {
                save(next)
            } catch (_: IOException) {
                data = next
            }
        }
        return true
    }

    @Synchronized
    fun logout(session: String) {
        val hash = sha256(session)
        if (data.sessions.none { it.sha256 == hash }) return
        save(data.copy(sessions = data.sessions.filterNot { it.sha256 == hash }))
        ended.value++
    }

    @Synchronized
    fun disconnectAll() {
        if (data.sessions.isEmpty()) return
        save(data.copy(sessions = emptyList()))
        ended.value++
    }

    /** Callers with failures on record (tests). */
    @Synchronized
    internal fun trackedCallers(): Int = perWho.size

    /** A new entry for [who]; past [MAX_CALLERS] the oldest entry goes. */
    private fun track(who: String): Failures {
        val f = Failures()
        perWho[who] = f
        if (perWho.size > MAX_CALLERS) perWho.remove(perWho.keys.first())
        return f
    }

    private fun fail(f: Failures, now: Long) {
        f.count++
        if (f.count >= FREE_FAILURES) {
            val seconds = minOf(MAX_WAIT_S, 1L shl minOf(f.count - FREE_FAILURES + 1, 20))
            f.blockedUntil = now + seconds * 1000
        }
        recentFailures.addLast(now)
        while (recentFailures.isNotEmpty() && now - recentFailures.first() > GLOBAL_WINDOW_MS) recentFailures.removeFirst()
        if (recentFailures.size >= GLOBAL_FAILURES) {
            lockedUntil = now + GLOBAL_WAIT_S * 1000
            recentFailures.clear()
        }
    }

    private fun passwordMatches(pw: String, d: RemoteData): Boolean {
        if (d.hash.isEmpty()) return false
        val actual = derive(pw, unhex(d.salt), d.iterations)
        return MessageDigest.isEqual(actual, unhex(d.hash))
    }

    private fun save(next: RemoteData) {
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(RemoteData.serializer(), next))
        AtomicFiles.replace(tmp, file)
        data = next
        count.value = next.sessions.size
    }

    private fun load(): RemoteData = runCatching {
        val d = json.decodeFromString(RemoteData.serializer(), file.readText())
        unhex(d.salt)
        unhex(d.hash)
        if (d.hash.isNotEmpty() && d.iterations <= 0) RemoteData() else d
    }.getOrDefault(RemoteData())

    private companion object {
        const val FILE_NAME = "remote.json"
        const val MIN_PASSWORD = 6
        const val ITERATIONS = 210_000
        const val SALT_BYTES = 16
        const val KEY_BITS = 256
        const val SESSION_BYTES = 32
        const val SEEN_EVERY_MS = 60_000L
        const val FREE_FAILURES = 3
        const val MAX_WAIT_S = 300L
        const val GLOBAL_FAILURES = 20
        const val GLOBAL_WINDOW_MS = 10 * 60_000L
        const val GLOBAL_WAIT_S = 60L
        const val MAX_CALLERS = 1_000
        val random = SecureRandom()
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

        fun derive(pw: String, salt: ByteArray, iterations: Int): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(pw.toCharArray(), salt, iterations, KEY_BITS)).encoded

        fun sha256(s: String): String = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
        fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        fun secondsUntil(until: Long, now: Long): Int = ((until - now + 999) / 1000).toInt()
    }
}

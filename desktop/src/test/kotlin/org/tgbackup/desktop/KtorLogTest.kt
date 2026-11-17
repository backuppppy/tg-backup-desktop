package org.tgbackup.desktop

import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KtorLogTest {
    private val seen = mutableListOf<Pair<Level, String>>()

    @AfterTest
    fun reset() {
        KtorLogSink.sink = { _, _, _, _ -> }
    }

    @Test
    fun warnAndErrorReachTheSinkInfoDoesNot() {
        KtorLogSink.sink = { level, _, msg, _ -> seen += level to msg }
        val log = LoggerFactory.getLogger("io.ktor.test")
        log.info("quiet")
        log.debug("quiet")
        log.warn("slow {} ms", 12)
        log.error("accept failed", IllegalStateException("boom"))
        assertEquals(listOf(Level.WARN to "slow 12 ms", Level.ERROR to "accept failed"), seen)
    }

    @Test
    fun aThrowingSinkNeverReachesKtor() {
        KtorLogSink.sink = { _, _, _, _ -> error("sink broke") }
        LoggerFactory.getLogger("io.ktor.test").error("x")
    }
}

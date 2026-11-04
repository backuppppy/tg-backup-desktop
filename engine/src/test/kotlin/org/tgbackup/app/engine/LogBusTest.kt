package org.tgbackup.app.engine

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LogBusTest {
    @Test
    fun rotationReplacesAnOldRotatedFile() {
        val dir = Files.createTempDirectory("logbus").toFile()
        val old = dir.resolve("log.old.txt").apply { writeText("old") }
        val log = dir.resolve("log.txt").apply { writeText("x".repeat(1_000_001)) }

        LogBus(dir).info("x")

        assertNotEquals("old", old.readText())
        assertTrue(log.length() < 1_000, "log.txt is ${log.length()} bytes")
    }
}

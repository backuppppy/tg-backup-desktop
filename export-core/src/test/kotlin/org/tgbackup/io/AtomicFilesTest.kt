package org.tgbackup.io

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtomicFilesTest {
    @Test
    fun replaceOverwritesAnExistingTarget() {
        val dir = Files.createTempDirectory("atomic").toFile()
        val target = dir.resolve("config.json").apply { writeText("a") }
        val tmp = dir.resolve("config.json.tmp").apply { writeText("b") }

        AtomicFiles.replace(tmp, target)

        assertEquals("b", target.readText())
        assertFalse(tmp.exists())
    }

    @Test
    fun missingTmpFileFailsFast() {
        val dir = Files.createTempDirectory("atomic").toFile()
        // Warm-up: the first call pays class loading, which is not what this test measures.
        runCatching { AtomicFiles.replace(dir.resolve("warmup.tmp"), dir.resolve("t.json")) }
        var thrown: Throwable? = null
        val start = System.nanoTime()
        try {
            AtomicFiles.replace(dir.resolve("missing.tmp"), dir.resolve("t.json"))
        } catch (e: Throwable) {
            thrown = e
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(thrown is NoSuchFileException, "expected NoSuchFileException, got $thrown")
        // A retried missing file would sleep 100+200+400+800 ms; 1 s separates that from a slow machine.
        assertTrue(ms < 1_000, "took $ms ms")
    }
}

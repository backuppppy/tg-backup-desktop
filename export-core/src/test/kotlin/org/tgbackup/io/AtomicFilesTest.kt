package org.tgbackup.io

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        val start = System.nanoTime()
        assertFailsWith<NoSuchFileException> {
            AtomicFiles.replace(dir.resolve("missing.tmp"), dir.resolve("t.json"))
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 100, "took $ms ms")
    }
}

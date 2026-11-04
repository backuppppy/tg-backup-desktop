package org.tgbackup.io

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}

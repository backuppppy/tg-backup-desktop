package org.tgbackup.app.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class StoreTest {
    private fun dataDir(): File =
        Files.createTempDirectory("store").resolve("תיקייה עם רווח").toFile().apply { mkdirs() }

    @Test
    fun configSurvivesTwoUpdatesAndAReload() {
        val dir = dataDir()
        val store = Store(dir)
        store.updateConfig { it.copy(jobs = listOf(Job(id = "j1"))) }
        store.updateConfig { it.copy(jobs = it.jobs + Job(id = "j2")) }

        assertEquals(listOf("j1", "j2"), Store(dir).config.value.jobs.map { it.id })
    }

    @Test
    fun progressSurvivesTwoSavesAndAReload() {
        val dir = dataDir()
        val store = Store(dir)
        store.saveProgress("j1") { Progress(lastId = 10) }
        store.saveProgress("j1") { it.copy(lastId = 20) }

        assertEquals(20L, Store(dir).progress("j1").lastId)
    }
}

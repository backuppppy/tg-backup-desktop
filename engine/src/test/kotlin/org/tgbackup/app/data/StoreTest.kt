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

    @Test
    fun progressWrittenBeforeTheRunTotalsLoadsWithZeros() {
        val dir = dataDir()
        // A progress file from before copiedTotal, runMillis and latestId existed.
        File(dir, "progress").apply { mkdirs() }.resolve("j1.json").writeText(
            """{"lastId": 123, "waveStart": 0, "waveEnd": 0, "workerLast": {}, "viaTakeout": true, "sourceId": -1001}""",
        )
        val p = Store(dir).progress("j1")

        assertEquals(123L, p.lastId)
        assertEquals(true, p.viaTakeout)
        assertEquals(-1001L, p.sourceId)
        assertEquals(0L, p.copiedTotal)
        assertEquals(0L, p.runMillis)
        assertEquals(0L, p.latestId)
    }

    @Test
    fun runTotalsSurviveAReload() {
        val dir = dataDir()
        Store(dir).saveProgress("j1") { it.copy(copiedTotal = 5, runMillis = 60_000, latestId = 900) }
        val p = Store(dir).progress("j1")

        assertEquals(5L, p.copiedTotal)
        assertEquals(60_000L, p.runMillis)
        assertEquals(900L, p.latestId)
    }
}

package org.tgbackup.desktop.server

import org.tgbackup.desktop.seedLongNames
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `TGB_DEV_FIXTURE=long-names` (Task 10): what the layout spec relies on. */
class LongNamesFixtureTest : RouteTestBase() {
    @AfterTest
    fun tearDown() = closeApp()

    private val hebrew = Regex("[\\u0590-\\u05FF]")
    private val latin = Regex("[A-Za-z]")

    @Test
    fun seedsA120CharMixedJobNameIdsWithLrmALongAccountAndLongLogLines() {
        seedLongNames(app)
        val config = app.store.config.value
        val names = config.jobs.map { it.name }
        assertTrue(names.any { it.length == 120 && hebrew.containsMatchIn(it) && latin.containsMatchIn(it) }, "$names")
        val ids = config.jobs.flatMap { listOf(it.source, it.target) }
        assertTrue(ids.any { it.startsWith("‎-100") }, "$ids")
        assertTrue(config.accounts.any { it.name.length >= 80 }, config.accounts.map { it.name }.toString())
        val log = app.log.entries.value.map { it.msg }
        assertTrue(log.count { it.length >= 150 } >= 2, "$log")
        // One line with no break opportunity: it has to wrap anywhere.
        assertTrue(log.any { line -> line.split(' ').any { it.length >= 80 } }, "$log")
    }

    @Test
    fun seedsRunTotalsForTheJobCards() {
        seedLongNames(app)
        val s1 = app.jobs.statuses.value.getValue("longjob1")
        assertEquals(listOf(45_210L, 12_340L, 8_045_000L, 50_000L), listOf(s1.lastId, s1.copiedTotal, s1.runMillis, s1.latestId))
        assertEquals(0L, s1.runningSince)
        // The second job's latest id is not known yet.
        val s2 = app.jobs.statuses.value.getValue("longjob2")
        assertTrue(s2.copiedTotal >= 1_000_000, "$s2")
        assertEquals(0L, s2.latestId)
        assertEquals(12_340L, app.store.progress("longjob1").copiedTotal)
    }
}

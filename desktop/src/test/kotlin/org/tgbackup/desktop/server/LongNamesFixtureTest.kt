package org.tgbackup.desktop.server

import org.tgbackup.desktop.seedLongNames
import kotlin.test.AfterTest
import kotlin.test.Test
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
}

package org.tgbackup.desktop.server

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tgbackup.export.ExportState
import org.tgbackup.export.ProcessingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Controller builds a new Processing instance on every progress tick; the stream must still be rate-capped. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportSamplingTest {
    private fun processing(i: Int) = ExportState.Processing(
        ProcessingState().apply {
            step = ProcessingState.Step.Dialogs
            itemIndex = i
            bytesLoaded = i * 10L
        },
    )

    @Test
    fun fiftyProcessingTicksInOneSecondAreSampled() = runTest {
        val states = MutableStateFlow<ExportState>(ExportState.Idle)
        val out = mutableListOf<Pair<Long, ExportStateDto>>()
        val job = launch { sampledExportStates(states).collect { out += testScheduler.currentTime to it } }
        runCurrent()
        repeat(50) { i ->
            states.value = processing(i)
            delay(20)
        }
        delay(400) // past the next tick, so the final values are sampled
        val processingEvents = out.map { it.second }.filterIsInstance<ExportStateDto.Processing>()
        assertTrue(processingEvents.size in 2..6, "Processing events: ${processingEvents.size}")
        assertEquals(49, processingEvents.last().state.itemIndex)
        assertEquals(490L, processingEvents.last().state.bytesLoaded)
        assertEquals(ExportStateDto.Idle, out.first().second)
        job.cancel()
    }

    @Test
    fun switchToFinishedIsSentAtOnce() = runTest {
        val states = MutableStateFlow<ExportState>(processing(0))
        val out = mutableListOf<Pair<Long, ExportStateDto>>()
        val job = launch { sampledExportStates(states).collect { out += testScheduler.currentTime to it } }
        runCurrent()
        delay(50)
        states.value = processing(1)
        delay(50)
        val pushedAt = testScheduler.currentTime
        states.value = ExportState.Finished("/out", 3, 4)
        runCurrent()
        val (at, last) = out.last()
        assertEquals(ExportStateDto.Finished("/out", 3, 4), last)
        assertEquals(pushedAt, at, "Finished must not wait for the 300 ms tick")
        job.cancel()
    }

    @Test
    fun unchangedProgressIsNotResent() = runTest {
        val states = MutableStateFlow<ExportState>(processing(7))
        val out = mutableListOf<ExportStateDto>()
        val job = launch { sampledExportStates(states).collect { out += it } }
        delay(2_000)
        assertEquals(1, out.size, out.toString())
        job.cancel()
    }
}

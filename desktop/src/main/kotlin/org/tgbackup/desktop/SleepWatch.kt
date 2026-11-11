package org.tgbackup.desktop

/**
 * Notices that the PC slept while work ran: DesktopApp calls [check] every [tickMs] while busy. A wall-clock step
 * longer than the tick by at least [gapMs] means the PC was asleep (the program could not run) for that long.
 */
class SleepWatch(
    private val clock: () -> Long,
    val tickMs: Long = 30_000,
    private val gapMs: Long = 120_000,
) {
    private var last: Long? = null

    /** Starts measuring from now (work started). */
    @Synchronized
    fun reset() {
        last = clock()
    }

    /** The gap since the previous [check] or [reset] (see [gap]); 0 the first time. */
    @Synchronized
    fun check(): Long {
        val now = clock()
        val prev = last
        last = now
        return if (prev == null) 0 else gap(prev, now)
    }

    /** The ms between two wall-clock reads beyond one [tickMs]; 0 when under [gapMs]. */
    fun gap(prevWall: Long, nowWall: Long): Long {
        val beyond = nowWall - prevWall - tickMs
        return if (beyond < gapMs) 0 else beyond
    }
}

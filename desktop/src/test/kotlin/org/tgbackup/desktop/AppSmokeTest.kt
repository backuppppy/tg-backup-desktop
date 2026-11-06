package org.tgbackup.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/** `TGB_SMOKE_HOLD_MS`: how long `--smoke` keeps the loaded window (CI takes two screenshots in that time). */
class AppSmokeTest {
    @Test
    fun withoutTheVariableTheHoldIsTheDefault() {
        for (v in listOf(null, "", "  ", "abc", "-5", "0")) assertEquals(AppSmoke.HOLD_MS, AppSmoke.holdMs(v), "value=$v")
    }

    @Test
    fun aValueIsUsedAndCappedAtOneMinute() {
        assertEquals(20_000L, AppSmoke.holdMs("20000"))
        assertEquals(1_000L, AppSmoke.holdMs(" 1000 "))
        assertEquals(AppSmoke.MAX_HOLD_MS, AppSmoke.holdMs("999999999"))
    }
}

package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageCountTest {
    private fun ms(y: Int, m: Int, d: Int): Long =
        java.util.Calendar.getInstance(java.util.Locale.ROOT).apply { clear(); set(y, m - 1, d, 12, 0) }.timeInMillis

    @Test fun isoWeekKeys() {
        assertEquals("2026-W48", UsageCount.weekKey(ms(2026, 11, 25)))
        assertEquals("2026-W01", UsageCount.weekKey(ms(2026, 1, 1)))
        assertEquals("2026-W01", UsageCount.weekKey(ms(2025, 12, 29)))
        assertEquals("2026-W53", UsageCount.weekKey(ms(2027, 1, 3)))
    }

    @Test fun firstRunSendsInstallAndWeekThenOnlyNewWeeks() {
        val now = ms(2026, 11, 25)
        assertEquals(listOf("install", "w:2026-W48"), UsageCount.due(false, "", now))
        assertEquals(emptyList<String>(), UsageCount.due(true, "w:2026-W48", now))
        assertEquals(listOf("w:2026-W49"), UsageCount.due(true, "w:2026-W48", ms(2026, 12, 2)))
    }
}

package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagTest {
    @Test fun keepsOnlyTheLatestNotesAndNamesTheFailure() {
        repeat(60) { Diag.note("step $it", IllegalStateException("boom $it")) }
        val lines = Diag.dump().lines()
        assertEquals(40, lines.size)
        assertTrue(lines.last().contains("step 59: IllegalStateException"))
        assertTrue(lines.first().contains("step 20"))
    }
}

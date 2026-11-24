package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedSourceTest {
    @Test fun sourcesCompareByValue() {
        assertEquals(SavedSource("a", "host · u", "xtream", "http://h", "u", "p"), SavedSource("a", "host · u", "xtream", "http://h", "u", "p"))
    }
}

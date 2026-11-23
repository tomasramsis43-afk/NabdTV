package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroRotationTest {
    private val items = listOf("a", "b", "c")

    @Test fun movesToTheNextAndWraps() {
        assertEquals("b", HeroRotation.next(items, "a"))
        assertEquals("a", HeroRotation.next(items, "c"))
    }

    @Test fun startsAtTheFirstWhenCurrentIsNotInTheList() {
        assertEquals("a", HeroRotation.next(items, "zzz"))
        assertEquals("a", HeroRotation.next(items, null))
    }

    @Test fun nothingToRotateWithFewerThanTwoItems() {
        assertNull(HeroRotation.next(emptyList<String>(), "a"))
        assertNull(HeroRotation.next(listOf("a"), "a"))
    }

    @Test fun waitsForIdleTimeAndThenForThePeriod() {
        assertFalse(HeroRotation.due(now = 14_000, lastInput = 0, lastRotate = 0))
        assertTrue(HeroRotation.due(now = 15_000, lastInput = 0, lastRotate = 0))
        // just rotated: wait a full period even though the user is still idle
        assertFalse(HeroRotation.due(now = 20_000, lastInput = 0, lastRotate = 15_000))
        assertTrue(HeroRotation.due(now = 25_000, lastInput = 0, lastRotate = 15_000))
    }

    @Test fun anyInputRestartsTheIdleWait() {
        assertFalse(HeroRotation.due(now = 30_000, lastInput = 20_000, lastRotate = 0))
    }
}

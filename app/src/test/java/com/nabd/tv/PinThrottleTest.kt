package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinThrottleTest {
    private var t = 1_000_000L
    private fun throttle() = PinThrottle(freeAttempts = 4) { t }

    @Test fun firstMissesAreFree() {
        val p = throttle()
        repeat(4) { p.recordMiss() }
        assertTrue(p.allowed())
    }

    @Test fun fifthMissLocksForThirtySecondsThenReopens() {
        val p = throttle()
        repeat(5) { p.recordMiss() }
        assertFalse(p.allowed()); assertEquals(30, p.waitSeconds())
        t += 30_000L
        assertTrue(p.allowed())
    }

    @Test fun lockoutGrowsWithEachFurtherMiss() {
        val p = throttle()
        repeat(5) { p.recordMiss() }; t += 30_000L
        p.recordMiss()
        assertEquals(60, p.waitSeconds())
    }

    @Test fun successResetsEverything() {
        val p = throttle()
        repeat(6) { p.recordMiss() }
        p.recordSuccess()
        assertTrue(p.allowed())
        repeat(4) { p.recordMiss() }
        assertTrue(p.allowed())
    }
}

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

    @Test fun stateIsReportedAndCanBeRestored() {
        var saved = 0 to 0L
        val a = PinThrottle(freeAttempts = 4, onChange = { m, b -> saved = m to b }) { t }
        repeat(5) { a.recordMiss() }
        assertFalse(a.allowed())
        // A new instance (app restarted) starting from the saved state is still locked.
        val b = PinThrottle(freeAttempts = 4, initialMisses = saved.first, initialBlockedUntil = saved.second) { t }
        assertFalse(b.allowed()); assertEquals(30, b.waitSeconds())
    }

    @Test fun successClearsSavedState() {
        var saved = 9 to 9L
        val p = PinThrottle(onChange = { m, b -> saved = m to b }) { t }
        p.recordSuccess()
        assertEquals(0 to 0L, saved)
    }
}

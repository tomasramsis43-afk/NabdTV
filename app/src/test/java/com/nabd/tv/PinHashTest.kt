package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHashTest {
    private val algo = "PBKDF2WithHmacSHA256"

    @Test fun sameInputsGiveSameHash() {
        assertEquals(PinHash.hash("1234", "aabb", 1000, algo), PinHash.hash("1234", "aabb", 1000, algo))
    }

    @Test fun saltAndPinChangeTheHash() {
        val base = PinHash.hash("1234", "aabb", 1000, algo)
        assertNotEquals(base, PinHash.hash("1235", "aabb", 1000, algo))
        assertNotEquals(base, PinHash.hash("1234", "aabc", 1000, algo))
    }

    @Test fun hashIs256BitsOfHex() {
        assertEquals(64, PinHash.hash("1234", "aabb", 1000, algo).length)
    }

    @Test fun sameComparesExactly() {
        assertTrue(PinHash.same("abcd", "abcd"))
        assertFalse(PinHash.same("abcd", "abce"))
        assertFalse(PinHash.same("abcd", "abc"))
    }
}

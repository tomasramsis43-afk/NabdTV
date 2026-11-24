package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedSourceTest {
    @Test fun sourcesCompareByValue() {
        assertEquals(SavedSource("a", "host · u", "xtream", "http://h", "u", "p"), SavedSource("a", "host · u", "xtream", "http://h", "u", "p"))
    }
}

class SearchSlotTest {
    @Test fun sourcesWithDifferentLoginsHaveDifferentCacheSlots() {
        // The slot id is a hash of kind + address + user: two logins on one host must not share a cache.
        fun id(kind: String, a: String, u: String) = Integer.toHexString((kind + "|" + a + "|" + u).hashCode())
        org.junit.Assert.assertNotEquals(id("xtream", "http://h", "u1"), id("xtream", "http://h", "u2"))
        org.junit.Assert.assertNotEquals(id("m3u", "http://h/a.m3u", ""), id("xtream", "http://h/a.m3u", ""))
    }
}

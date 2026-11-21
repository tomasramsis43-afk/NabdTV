package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNextKeyTest {
    private val url = "http://host:8080/movie/user123/pass456/42.mkv"

    @Test fun keyIsStableForTheSameUrl() = assertEquals(WatchNext.keyOf(url), WatchNext.keyOf(url))

    @Test fun differentUrlsGetDifferentKeys() = assertNotEquals(WatchNext.keyOf(url), WatchNext.keyOf("$url?x=1"))

    @Test fun keyIsOpaqueHexAndNeverContainsTheUrlOrCredentials() {
        val k = WatchNext.keyOf(url)
        assertTrue(Regex("[0-9a-f]{24}").matches(k))
        assertFalse(k.contains("pass456") || k.contains("user123"))
    }
}

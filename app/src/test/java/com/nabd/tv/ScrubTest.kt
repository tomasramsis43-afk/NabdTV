package com.nabd.tv

import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubTest {
    @Test
    fun scrubsPasswordsAndTokens() {
        val t = "url=http://x/live/u/p/1.ts?password=secret&token=abc&client_secret=cs"
        val c = Scrub.clean(t)
        assertTrue(!c.contains("=secret"))
        assertTrue(!c.contains("=abc"))
        assertTrue(!c.contains("=cs"))
        assertTrue(c.contains("password=***"))
        assertTrue(c.contains("token=***"))
        assertTrue(c.contains("client_secret=***"))
    }

    @Test
    fun scrubsXtreamPath() {
        val t = "http://h/live/user/pass/123.ts"
        val c = Scrub.clean(t)
        assertTrue(c.contains("/live/***/***/"))
        assertTrue(!c.contains("user"))
    }
}
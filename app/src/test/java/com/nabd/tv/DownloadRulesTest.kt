package com.nabd.tv

import org.junit.Assert.*
import org.junit.Test

class DownloadRulesTest {
    @Test fun moviesAndEpisodesAreDownloadable() {
        assertTrue(DownloadRules.isDownloadable("http://h.tv:8080/movie/u/p/123.mkv"))
        assertTrue(DownloadRules.isDownloadable("https://h.tv/series/u/p/456.mp4?x=1"))
        assertTrue(DownloadRules.isDownloadable("https://cdn.example.com/files/film.mp4"))
    }

    @Test fun liveAndStreamsAreNot() {
        assertFalse(DownloadRules.isDownloadable("http://h.tv/live/u/p/1.ts"))
        assertFalse(DownloadRules.isDownloadable("http://h.tv/live/u/p/1.m3u8"))
        assertFalse(DownloadRules.isDownloadable("https://h.tv/movie/u/p/1.m3u8"))
        assertFalse(DownloadRules.isDownloadable("series:55"))
        assertFalse(DownloadRules.isDownloadable("file:///sdcard/a.mp4"))
    }

    @Test fun extensionAndId() {
        assertEquals("mkv", DownloadRules.extFor("http://h/movie/u/p/1.mkv?token=a"))
        assertEquals("mp4", DownloadRules.extFor("http://h/movie/u/p/1"))
        assertEquals(DownloadRules.idFor("http://a/1.mp4"), DownloadRules.idFor("http://a/1.mp4"))
        assertNotEquals(DownloadRules.idFor("http://a/1.mp4"), DownloadRules.idFor("http://a/2.mp4"))
    }

    @Test fun percentIsClamped() {
        assertEquals(50, DownloadRules.percent(50, 100))
        assertEquals(0, DownloadRules.percent(10, 0))
        assertEquals(100, DownloadRules.percent(200, 100))
    }

    @Test fun blockPlanCoversTheWholeFileExactly() {
        val b = 8L * 1024 * 1024
        val total = 20L * 1024 * 1024 + 123
        assertEquals(3, DownloadRules.blockCount(total))
        assertEquals(0L, DownloadRules.blockStart(0))
        assertEquals(b - 1, DownloadRules.blockEnd(0, total))
        assertEquals(b, DownloadRules.blockStart(1))
        assertEquals(total - 1, DownloadRules.blockEnd(2, total))
        var sum = 0L
        for (i in 0 until DownloadRules.blockCount(total)) sum += DownloadRules.blockEnd(i, total) - DownloadRules.blockStart(i) + 1
        assertEquals(total, sum)
    }

    @Test fun exactMultipleAndEmptyBlockPlans() {
        val b = DownloadRules.BLOCK_BYTES
        assertEquals(2, DownloadRules.blockCount(2 * b))
        assertEquals(2 * b - 1, DownloadRules.blockEnd(1, 2 * b))
        assertEquals(0, DownloadRules.blockCount(0))
    }
}

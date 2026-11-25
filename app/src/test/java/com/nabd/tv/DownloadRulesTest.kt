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
}

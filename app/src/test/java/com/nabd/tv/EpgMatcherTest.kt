package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgMatcherTest {
    private fun ch(id: Int, name: String, tvg: String? = null) = Channel(id, name, "http://s/$id", null, "g", tvgId = tvg)
    private val index = EpgParser.ChannelIndex(
        ids = mapOf("bein1.qa" to "beIN1.qa", "mbc1" to "MBC1"),
        names = mapOf(Classifier.normalize("MBC 1 HD") to "MBC1")
    )

    @Test fun tvgIdMatchesCaseInsensitively() {
        val m = EpgMatcher.match(listOf(ch(1, "Whatever", tvg = "BEIN1.QA")), index, emptyMap())
        assertEquals("beIN1.qa", m["http://s/1"])
    }

    @Test fun fallsBackToTheNormalisedName() {
        val m = EpgMatcher.match(listOf(ch(2, "MBC 1 FHD")), index, emptyMap())
        assertEquals("MBC1", m["http://s/2"])
    }

    @Test fun manualOverrideWinsOverAutomaticMatching() {
        val m = EpgMatcher.match(listOf(ch(3, "MBC 1 HD", tvg = "mbc1")), index, mapOf("http://s/3" to "Custom.id"))
        assertEquals("Custom.id", m["http://s/3"])
    }

    @Test fun unmatchedChannelsAreLeftOut() {
        val m = EpgMatcher.match(listOf(ch(4, "Unknown channel")), index, emptyMap())
        assertTrue(m.isEmpty())
    }
}

class PrefetchTest {
    @Test fun onlyHlsPlaylistsArePrefetched() {
        assertTrue(Prefetch.isHls("http://x/live/1.m3u8"))
        assertTrue(Prefetch.isHls("http://x/live/1.M3U8?token=a.ts"))
        assertFalse(Prefetch.isHls("http://x/live/1.ts"))
        assertFalse(Prefetch.isHls("http://x/movie/1.mkv"))
    }
}

package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {
    private val sample = """
        #EXTM3U url-tvg="http://epg.example/a.xml,http://epg.example/b.xml"
        #EXTINF:-1 tvg-id="news.one" tvg-logo="http://img/n1.png" group-title="News" catchup-days="3" tvg-chno="12",News One
        http://host/live/n1.m3u8
        #EXTINF:-1 group-title="Sports",Sports HD
        http://host/live/s1.ts
        http://host/orphan.ts
        #EXTINF:-1,
        http://host/live/x.ts
    """.trimIndent()

    @Test fun parsesAttributesAndName() {
        val c = M3uParser.parse(sample).first()
        assertEquals("News One", c.name)
        assertEquals("News", c.group)
        assertEquals("news.one", c.tvgId)
        assertEquals("http://img/n1.png", c.logo)
        assertEquals("http://host/live/n1.m3u8", c.url)
        assertEquals(3, c.archiveDays)
        assertEquals(12, c.number)
    }

    @Test fun ignoresUrlsWithoutExtinf() {
        val urls = M3uParser.parse(sample).map { it.url }
        assertEquals(listOf("http://host/live/n1.m3u8", "http://host/live/s1.ts", "http://host/live/x.ts"), urls)
    }

    @Test fun usesDefaultsWhenAttributesMissing() {
        val c = M3uParser.parse(sample)[1]
        assertEquals("Sports HD", c.name)
        assertNull(c.tvgId)
        assertNull(c.logo)
        assertEquals(0, c.archiveDays)
        assertEquals("Channel", M3uParser.parse(sample).last().name)
        assertEquals("Other", M3uParser.parse(sample).last().group)
    }

    @Test fun readsFirstEpgUrlFromHeader() {
        assertEquals("http://epg.example/a.xml", M3uParser.epgFromHeader(sample))
        assertNull(M3uParser.epgFromHeader("#EXTM3U\n"))
    }
}

class M3uNameTest {
    @Test fun quoteInsideTitleDoesNotCutTheName() {
        val l = """#EXTINF:-1 tvg-id="a" group-title="News",Channel "Plus" HD"""
        assertEquals("Channel \"Plus\" HD", M3uParser.displayName(l))
    }

    @Test fun commaInsideAttributeIsIgnored() {
        val l = """#EXTINF:-1 group-title="News, Sports",Real Name"""
        assertEquals("Real Name", M3uParser.displayName(l))
    }

    @Test fun parseKeepsQuotedTitleAndUrl() {
        val c = M3uParser.parse("#EXTM3U\n#EXTINF:-1 tvg-id=\"a\",Show \"X\"\nhttp://h/1.ts").single()
        assertEquals("Show \"X\"", c.name)
        assertEquals("http://h/1.ts", c.url)
    }
}

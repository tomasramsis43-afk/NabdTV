package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic, local data only: no network and no real IPTV server. */
class M3uParserFileTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun parseFileMatchesParseText() {
        val text = "#EXTM3U\n#EXTINF:-1 group-title=\"أخبار\",قناة الأخبار\nhttp://h/1.ts\nbroken line\n#EXTINF:-1,No url\n"
        val f = tmp.newFile("a.m3u").apply { writeText(text, Charsets.UTF_8) }
        val fromFile = M3uParser.parseFile(f)
        assertEquals(M3uParser.parse(text), fromFile)
        assertEquals("قناة الأخبار", fromFile.single().name)
    }

    @Test fun largeListParsesAllChannels() {
        val n = 50_000
        val f = tmp.newFile("big.m3u")
        f.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("#EXTM3U\n")
            for (i in 0 until n) w.write("#EXTINF:-1 tvg-id=\"c$i\" group-title=\"G${i % 50}\",Channel $i\nhttp://h/$i.ts\n")
        }
        assertEquals(n, M3uParser.parseFile(f).size)
    }

    @Test fun emptyAndGarbageFilesGiveEmptyList() {
        assertEquals(0, M3uParser.parseFile(tmp.newFile("empty.m3u")).size)
        val g = tmp.newFile("garbage.m3u").apply { writeText("\u0000\u0001 not a playlist\n<html>\n") }
        assertEquals(0, M3uParser.parseFile(g).size)
    }
}

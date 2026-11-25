package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchesTest {
    private val H = 3_600_000L
    private fun ch(id: Int, name: String, group: String = "Sports") = Channel(id, name, "u$id", null, group)

    @Test fun parsesCommonFixtureShapes() {
        assertEquals("Arsenal" to "Chelsea", Matches.parse("Arsenal vs Chelsea"))
        assertEquals("Arsenal" to "Chelsea", Matches.parse("Live: Arsenal v Chelsea"))
        assertEquals("الاهلي" to "الزمالك", Matches.parse("مباراة الاهلي × الزمالك"))
        assertEquals("الهلال" to "النصر", Matches.parse("الدوري السعودي: الهلال ضد النصر (مباشر)"))
        assertEquals("Arsenal" to "Chelsea", Matches.parse("Arsenal v Chelsea - Premier League"))
    }

    @Test fun rejectsReplaysAndPlainTitles() {
        assertNull(Matches.parse("Arsenal vs Chelsea replay"))
        assertNull(Matches.parse("ملخص مباراة الاهلي × الزمالك"))
        assertNull(Matches.parse("Evening News"))
        assertNull(Matches.parse("Premier League - Match of the day"))
    }

    @Test fun sameFixtureOnTwoChannelsIsOneEntryAndFinishedOnesAreDropped() {
        val now = 100 * H
        val epg = mapOf(
            "u1" to listOf(Prog(now - H, now + H, "Arsenal vs Chelsea")),
            "u2" to listOf(Prog(now - H + 600_000, now + H, "Live: Arsenal v Chelsea")),
            "u3" to listOf(Prog(now - 5 * H, now - 3 * H, "Barcelona vs Real Madrid"))
        )
        val r = Matches.detect(epg, listOf(ch(1, "Sport 1"), ch(2, "Sport 2"), ch(3, "Sport 3")), now)
        assertEquals(1, r.size)
        assertEquals(2, r[0].channels.size)
        assertTrue(r[0].isLive(now))
    }

    @Test fun movieChannelsAreIgnoredUnlessTheTitleLooksLikeAMatch() {
        val now = 100 * H
        val epg = mapOf("u1" to listOf(Prog(now, now + 2 * H, "Batman vs Superman")))
        assertTrue(Matches.detect(epg, listOf(ch(1, "Cinema One", "Movies")), now).isEmpty())
    }

    @Test fun liveFirstThenByStart() {
        val now = 100 * H
        val epg = mapOf(
            "u1" to listOf(Prog(now + 2 * H, now + 4 * H, "Milan vs Inter"), Prog(now - H, now + H, "Roma vs Lazio")),
            "u2" to listOf(Prog(now + H, now + 3 * H, "Ajax vs PSV"))
        )
        val r = Matches.detect(epg, listOf(ch(1, "Sport 1"), ch(2, "Sport 2")), now)
        assertEquals(listOf("Roma", "Ajax", "Milan"), r.map { it.home })
    }
}

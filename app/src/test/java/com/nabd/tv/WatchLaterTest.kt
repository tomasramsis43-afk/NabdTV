package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchLaterTest {
    @Test fun newestFirstAndMalformedEntriesSkipped() {
        val l = VmLogic.parseWatchLater(setOf("100|a", "300|b|with|bars", "200|c", "oops", "|x", "xx|y"))
        assertEquals(listOf("b|with|bars", "c", "a"), l)
    }

    @Test fun followedSeriesOrderDedupeAndCap() {
        val f = VmLogic.followedSeries(
            favorites = listOf("series:1", "live:9", "series:2"),
            watched = listOf("series:2", "series:3"),
            later = listOf("movie:7", "series:4", "series:1")
        )
        assertEquals(listOf("series:1", "series:2", "series:3", "series:4"), f)
        assertEquals(2, VmLogic.followedSeries(listOf("series:1", "series:2", "series:3"), emptyList(), emptyList(), cap = 2).size)
    }

    @Test fun newestEpisodeIsHighestSeasonThenNumber() {
        val eps = listOf(Episode(1, 9, "a", "u1"), Episode(2, 1, "b", "u2"), Episode(2, 3, "c", "u3"), Episode(1, 12, "d", "u4"))
        assertEquals("u3", VmLogic.newestEpisode(eps)?.url)
        assertNull(VmLogic.newestEpisode(emptyList()))
    }
}

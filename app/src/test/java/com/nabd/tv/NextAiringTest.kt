package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class NextAiringTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val noon = NextAiring.midnightOf("2026-12-01", utc)!! + 12 * 3_600_000L

    private fun tmdb(status: String, next: String?) =
        """{"status":"$status","next_episode_to_air":${next ?: "null"}}"""

    @Test fun tmdbDateOnlyNextEpisode() {
        val n = NextAiring.parseTmdb(tmdb("Returning Series", """{"air_date":"2026-12-05","episode_number":4,"season_number":2}"""), noon, utc)!!
        assertEquals(4, n.episode); assertEquals(2, n.season); assertFalse(n.exact)
        assertEquals(4, NextAiring.dayDiff(n.atMs, noon, utc))
    }

    @Test fun tmdbNothingToShow() {
        val ok = """{"air_date":"2026-12-05","episode_number":4,"season_number":2}"""
        assertNull(NextAiring.parseTmdb(tmdb("Ended", ok), noon, utc))
        assertNull(NextAiring.parseTmdb(tmdb("Canceled", ok), noon, utc))
        assertNull(NextAiring.parseTmdb(tmdb("Returning Series", null), noon, utc))
        assertNull(NextAiring.parseTmdb(tmdb("Returning Series", """{"air_date":"2026-12-05","episode_number":0}"""), noon, utc))
        assertNull(NextAiring.parseTmdb(tmdb("Returning Series", """{"air_date":"","episode_number":3}"""), noon, utc))
        assertNull(NextAiring.parseTmdb(tmdb("Returning Series", """{"air_date":"2026-11-20","episode_number":3}"""), noon, utc))   // date already passed
    }

    @Test fun todayAndTomorrow() {
        assertEquals(0, NextAiring.dayDiff(NextAiring.midnightOf("2026-12-01", utc)!!, noon, utc))
        assertEquals(1, NextAiring.dayDiff(NextAiring.midnightOf("2026-12-02", utc)!!, noon, utc))
        assertNull(NextAiring.midnightOf("2026-13-45", utc))
        assertNull(NextAiring.midnightOf("tomorrow", utc))
    }

    private fun ani(romaji: String, english: String, at: Long, ep: Int) =
        """{"data":{"Page":{"media":[{"title":{"romaji":"$romaji","english":"$english"},"synonyms":[],"nextAiringEpisode":{"airingAt":$at,"episode":$ep}}]}}}"""

    @Test fun anilistExactTime() {
        val now = 1_799_000_000_000L
        val n = NextAiring.parseAniList(ani("Shingeki no Kyojin", "Attack on Titan", 1_800_000_000L, 5), "Attack on Titan", now)!!
        assertEquals(5, n.episode); assertTrue(n.exact); assertEquals(1_800_000_000_000L, n.atMs)
    }

    @Test fun anilistRejectsDifferentShows() {
        val now = 1_799_000_000_000L
        assertNull(NextAiring.parseAniList(ani("Dragon Ball Daima", "Dragon Ball Daima", 1_800_000_000L, 5), "Dragon Ball", now))
        assertNotNullFor(ani("X", "Attack on Titan Season 2", 1_800_000_000L, 3), "Attack on Titan", now)
        assertNull(NextAiring.parseAniList(ani("A", "Attack on Titan", 1_790_000_000L, 5), "Attack on Titan", now))   // aired long ago
        assertNull(NextAiring.parseAniList("""{"data":{"Page":{"media":[]}}}""", "Attack on Titan", now))
    }

    private fun assertNotNullFor(body: String, base: String, now: Long) =
        assertTrue(NextAiring.parseAniList(body, base, now) != null)

    @Test fun whenIsShownInTheGivenTimeZone() {
        val at = NextAiring.midnightOf("2026-12-05", utc)!! + (18 * 60 + 30) * 60_000L   // 18:30 UTC, a Saturday
        val exact = NextAir(5, null, at, true, "anilist")
        assertEquals("Sat 5 Dec · 18:30", NextAiring.formatWhen(exact, java.util.Locale.ENGLISH, true, utc))
        assertEquals("Sat 5 Dec · 6:30 PM", NextAiring.formatWhen(exact, java.util.Locale.ENGLISH, false, utc))
        // same instant for a viewer in Riyadh (UTC+3): 21:30 the same day
        assertEquals("Sat 5 Dec · 21:30", NextAiring.formatWhen(exact, java.util.Locale.ENGLISH, true, TimeZone.getTimeZone("Asia/Riyadh")))
        // a date-only (TMDB) answer never gets an invented time
        val dateOnly = NextAir(4, 2, NextAiring.midnightOf("2026-12-05", utc)!!, false, "tmdb")
        assertEquals("Sat 5 Dec", NextAiring.formatWhen(dateOnly, java.util.Locale.ENGLISH, true, utc))
    }
}

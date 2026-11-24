package com.nabd.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class UpcomingTest {
    private val ar = JSONArray(
        """[
          {"id":1,"title":"فيلم تجريبي","original_title":"Test Movie","release_date":"2026-12-20","overview":"","poster_path":"/p.jpg","backdrop_path":null,"popularity":50.5,"vote_count":3,"vote_average":9.0},
          {"id":2,"title":"Old","original_title":"Old","release_date":"2026-11-01","poster_path":"/o.jpg"},
          {"id":3,"title":"NoPic","original_title":"NoPic","release_date":"2026-12-25"},
          {"id":4,"title":"BadDate","original_title":"BadDate","release_date":"","poster_path":"/b.jpg"}
        ]"""
    )
    private val en = JSONArray(
        """[{"id":1,"title":"Test Movie","original_title":"Test Movie","release_date":"2026-12-20","overview":"A plot","poster_path":"/p.jpg","backdrop_path":"/b.jpg","popularity":48.0,"vote_count":120,"vote_average":7.8}]"""
    )
    private val pages = listOf("ar" to ar, "en-US" to en)

    @Test fun mergesLanguagesAndDropsNoise() {
        val l = Upcoming.merge(true, pages, "ar", "2026-11-24")
        assertEquals(1, l.size)                         // past date, no picture and empty date are gone
        val u = l[0]
        assertEquals("فيلم تجريبي", u.title)             // app language first
        assertEquals("A plot", u.overview)              // Arabic summary was empty: falls back to English
        assertTrue(LogoMatch.norm("Test Movie") in u.names && LogoMatch.norm("فيلم تجريبي") in u.names)
        assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", u.poster)
        assertEquals("https://image.tmdb.org/t/p/w780/b.jpg", u.backdrop)
        assertEquals(50.5, u.popularity, 0.001)
        assertEquals(7.8, u.rating!!, 0.001)            // the 3-vote rating is ignored
        assertEquals(2026, u.year)
        assertEquals("Test Movie", Upcoming.merge(true, pages, "en", "2026-11-24")[0].title)
    }

    @Test fun detailParsing() {
        val o = JSONObject(
            """{"genres":[{"id":1,"name":"Action"}],"runtime":0,"episode_run_time":[45],"tagline":"T","overview":"O",
               "videos":{"results":[{"site":"YouTube","type":"Teaser","key":"tz"},{"site":"Vimeo","type":"Trailer","key":"v"},{"site":"YouTube","type":"Trailer","key":"tr"}]},
               "credits":{"cast":[{"name":"A"},{"name":"B"}]}}"""
        )
        val d = Upcoming.parseDetail(o)
        assertEquals(listOf("Action"), d.genres); assertEquals(45, d.runtimeMin); assertEquals("tr", d.trailerId)
        assertEquals(listOf("A", "B"), d.cast); assertEquals("T", d.tagline); assertEquals("O", d.overview)
        assertEquals("", Upcoming.parseDetail(JSONObject("{}")).trailerId)
    }

    private fun lib(name: String) = Channel(1, name, "http://x/movie/1.mp4", null, "g")

    @Test fun titlesTheLibraryAlreadyHasAreRemoved() {
        val idx = TmdbCharts.indexOf(listOf(lib("Dune Part Two (2024)")))
        fun inLibrary(year: Int) = TmdbCharts.pick(idx, listOf(TmdbCharts.Entry(setOf(LogoMatch.norm("Dune Part Two")), year, null, null)), 1) { true }.first.isNotEmpty()
        assertTrue(inLibrary(2024))     // same title and year: it moved from "coming soon" to "available"
        assertTrue(!inLibrary(2026))    // same name, a different (future) film: stays in "coming soon"
    }

    @Test fun releaseDates() {
        val utc = TimeZone.getTimeZone("UTC")
        val now = NextAiring.midnightOf("2026-12-01", utc)!! + 12 * 3_600_000L
        assertEquals(4, Upcoming.daysUntil("2026-12-05", now, utc))
        assertEquals("Sat 5 Dec 2026", Upcoming.dateLabel("2026-12-05", Locale.ENGLISH, utc))
        assertNull(Upcoming.daysUntil("soon", now, utc))
    }
}

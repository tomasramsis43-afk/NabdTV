package com.nabd.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoMatchTest {
    private fun item(id: String, label: String, p31: String, year: Int?, logo: String?, desc: String = "") = """
        "$id": {"labels": {"en": {"value": "$label"}}, "descriptions": {"en": {"value": "$desc"}}, "aliases": {},
          "claims": {"P31": [{"mainsnak": {"datavalue": {"value": {"id": "$p31"}}}}]
            ${if (year != null) ""","P577": [{"mainsnak": {"datavalue": {"value": {"time": "+$year-01-01T00:00:00Z"}}}}]""" else ""}
            ${if (logo != null) ""","P154": [{"mainsnak": {"datavalue": {"value": "$logo"}}}]""" else ""}}}"""

    private fun run(title: String, kind: Kind, year: Int?, vararg items: Triple<String, String, String>, ents: String): LogoMatch.Pick {
        val search = JSONObject("""{"search": [${items.joinToString(",") { """{"id": "${it.first}", "label": "${it.second}"}""" }}]}""")
        return LogoMatch.pick(title, kind, year, search, JSONObject("{$ents}"))
    }

    @Test fun cleansProviderTitles() {
        assertEquals("Breaking Bad" to 2008, LogoMatch.clean("Breaking Bad (2008)"))
        assertEquals("Breaking Bad" to 2008, LogoMatch.clean("AR | Breaking Bad [2008] 4K"))
        assertEquals("Joker" to 2019, LogoMatch.clean("Joker - 2019"))
        assertEquals("Dark" to null, LogoMatch.clean("Dark"))
    }

    @Test fun matchesExactTitleKindAndYear() {
        val e = item("Q1", "Breaking Bad", "Q5398426", 2008, "Breaking Bad logo.svg")
        val p = run("Breaking Bad", Kind.Series, 2008, Triple("Q1", "Breaking Bad", ""), ents = e)
        assertEquals(LogoMatch.Pick.Found("Q1", "Breaking Bad logo.svg"), p)
    }

    @Test fun rejectsWrongKindAndWrongYear() {
        val e = item("Q1", "Joker", "Q11424", 2019, "Joker logo.png")
        assertTrue(run("Joker", Kind.Series, null, Triple("Q1", "Joker", ""), ents = e) is LogoMatch.Pick.None)
        assertTrue(run("Joker", Kind.Movie, 1990, Triple("Q1", "Joker", ""), ents = e) is LogoMatch.Pick.None)
        assertTrue(run("Joker", Kind.Movie, 2019, Triple("Q1", "Joker", ""), ents = e) is LogoMatch.Pick.Found)
    }

    @Test fun ambiguousTitlesGetNoLogo() {
        val e = item("Q1", "The Office", "Q5398426", 2005, "US.svg") + "," + item("Q2", "The Office", "Q5398426", 2001, "UK.svg")
        val p = run("The Office", Kind.Series, null, Triple("Q1", "The Office", ""), Triple("Q2", "The Office", ""), ents = e)
        assertEquals(LogoMatch.Pick.None("ambiguous title"), p)
        // a year disambiguates
        assertTrue(run("The Office", Kind.Series, 2005, Triple("Q1", "The Office", ""), Triple("Q2", "The Office", ""), ents = e) is LogoMatch.Pick.Found)
    }

    @Test fun rejectsJpgAndFilmSeriesAndDifferentTitles() {
        val jpg = item("Q1", "Narcos", "Q5398426", 2015, "Narcoslogo.jpg")
        assertEquals(LogoMatch.Pick.None("logo file is not svg/png"), run("Narcos", Kind.Series, 2015, Triple("Q1", "Narcos", ""), ents = jpg))
        val fs = item("Q1", "Avatar", "Q24856", 2009, "x.svg", "film series")
        assertTrue(run("Avatar", Kind.Movie, 2009, Triple("Q1", "Avatar", ""), ents = fs) is LogoMatch.Pick.None)
        val other = item("Q1", "Inception Point", "Q11424", 2010, "x.svg")
        assertTrue(run("Inception", Kind.Movie, 2010, Triple("Q1", "Inception Point", ""), ents = other) is LogoMatch.Pick.None)
    }

    @Test fun noLogoOnWikidataMeansNoLogo() {
        val e = item("Q1", "باب الحارة", "Q5398426", 2006, null)
        assertEquals(LogoMatch.Pick.None("no logo on Wikidata"), run("باب الحارة", Kind.Series, 2006, Triple("Q1", "باب الحارة", ""), ents = e))
    }

    private fun px(w: Int, h: Int, f: (Int, Int) -> Int) = IntArray(w * h) { f(it % w, it / w) }

    @Test fun acceptsTransparentWordmarkRejectsPhotos() {
        val ink = 0xFFFFFFFF.toInt()
        val logo = px(100, 40) { x, y -> if (x in 10..90 && y in 15..25) ink else 0 }
        assertTrue(LogoMatch.looksLikeLogo(100, 40, logo))
        val photo = px(100, 40) { _, _ -> 0xFF336699.toInt() }
        assertFalse(LogoMatch.looksLikeLogo(100, 40, photo))
        val tall = px(40, 100) { x, y -> if (x in 10..30 && y in 10..90) ink else 0 }
        assertFalse(LogoMatch.looksLikeLogo(40, 100, tall))
        val tight = px(100, 40) { _, y -> if (y in 15..25) ink else 0 }          // letters touch the left/right edges
        assertTrue(LogoMatch.looksLikeLogo(100, 40, tight))
        val solidBanner = px(100, 40) { x, y -> if (x in 10..90 && y in 15..25) ink else 0xFF101010.toInt() }  // alpha channel but opaque background
        assertFalse(LogoMatch.looksLikeLogo(100, 40, solidBanner))
        val empty = px(100, 40) { _, _ -> 0 }
        assertFalse(LogoMatch.looksLikeLogo(100, 40, empty))
    }

    @Test fun arabicSpellingVariantsMatch() {
        assertEquals(LogoMatch.norm("الاختيار"), LogoMatch.norm("الإختيار"))
        assertEquals(LogoMatch.norm("أولاد رزق"), LogoMatch.norm("اولاد رزق"))
        assertEquals(LogoMatch.norm("باب الحارة"), LogoMatch.norm("بـاب  الحاره"))
        assertEquals(LogoMatch.norm("جعفر العمدة"), LogoMatch.norm("جعفر العمده"))
        assertEquals("breakingbad", LogoMatch.norm("Breaking-Bad!"))
        assertFalse(LogoMatch.norm("الفيل الأزرق") == LogoMatch.norm("الفيل الأزرق 2"))
    }
}

package com.nabd.tv

import org.json.JSONObject

/**
 * Pure rules that decide whether a Wikidata item is the SAME title the provider lists, and whether a downloaded
 * image really is a transparent title logo. No Android or network code here, so it is unit-tested.
 *
 * Source: Wikidata property P154 ("logo image") -> a Wikimedia Commons file. Free, no account, no API key.
 */
object LogoMatch {
    private val FILM = setOf("Q11424", "Q24862", "Q202866", "Q506240", "Q226730", "Q24869", "Q157443", "Q2431196",
        "Q20650540", "Q1054574", "Q4263842", "Q8274", "Q93204", "Q645928")
    private val SERIES = setOf("Q5398426", "Q1259759", "Q526877", "Q15416", "Q63952888", "Q21191270", "Q117467246",
        "Q581714", "Q1107", "Q196600", "Q15961987", "Q47433", "Q1983062", "Q28464168", "Q3464665", "Q43232")
    /** "film series" / "franchise" are not a single film. */
    private val NOT_FILM = setOf("Q24856", "Q1198113")
    private val SERIES_DESC = Regex("(television|tv|web|anime|animated) (series|show)|miniseries|tv series")
    private val FILM_DESC = Regex("\\b(film|movie)\\b(?! series)")

    sealed class Pick {
        data class Found(val qid: String, val file: String) : Pick()
        data class None(val reason: String) : Pick()
    }

    /** Lower-case letters and digits only; Arabic spelling variants (hamza forms, ة/ه, ى/ي, tatweel) collapse to one form. */
    // Compiled once: norm()/clean() run for every title of the library, so building Regex objects per call froze weak TV boxes.
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]")
    private val PREFIX = Regex("^\\s*[\\[(]?[A-Za-z]{2,4}[\\])]?\\s*[|\\uFF5C]\\s*")
    private val YEAR_IN_BRACKETS = Regex("[\\[(]\\s*((?:19|20)\\d{2})\\s*[\\])]")
    private val BRACKETS = Regex("[\\[(].*?[\\])]")
    private val YEAR_AT_END = Regex("[\\s\\-\\u2013]+((?:19|20)\\d{2})\\s*$")
    private val QUALITY = Regex("\\b(4K|UHD|FHD|HD|SD|1080p|720p|HDR|BluRay|WEB-?DL)\\b", RegexOption.IGNORE_CASE)
    private val DUB_TAGS = Regex("(\u0645\u062A\u0631\u062C\u0645|\u0645\u062F\u0628\u0644\u062C|\u0645\u062F\u0628\u0644\u062C\u0629)")
    private val SPACES = Regex("\\s+")

    fun norm(t: String): String = t.lowercase()
        .replace('\u0623', '\u0627').replace('\u0625', '\u0627').replace('\u0622', '\u0627').replace('\u0671', '\u0627')
        .replace('\u0629', '\u0647').replace('\u0649', '\u064A').replace("\u0640", "")
        .replace(NON_ALNUM, "")

    fun hasArabic(t: String) = t.any { it in '\u0600'..'\u06FF' }

    /** Splits a provider title into the bare title and a release year when one is embedded ("Title (2019)", "Title 2019"). */
    fun clean(raw: String): Pair<String, Int?> {
        var s = raw.trim()
        var year: Int? = null
        s = s.replace(PREFIX, "")          // "AR | Title"
        YEAR_IN_BRACKETS.find(s)?.let { year = it.groupValues[1].toInt() }
        s = s.replace(BRACKETS, " ")
        YEAR_AT_END.find(s)?.let {
            if (year == null) year = it.groupValues[1].toInt()
            s = s.removeRange(it.range)
        }
        s = s.replace(QUALITY, " ")
        s = s.replace(DUB_TAGS, " ")
        s = s.replace(SPACES, " ").trim(' ', '-', '\u2013', '|', ':')
        return s to year
    }

    private fun years(claims: JSONObject, pid: String): List<Int> {
        val arr = claims.optJSONArray(pid) ?: return emptyList()
        val out = ArrayList<Int>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("time")
            if (t != null && t.length >= 5) t.substring(1, 5).toIntOrNull()?.let(out::add)
        }
        return out
    }

    private fun strings(claims: JSONObject, pid: String): List<String> {
        val arr = claims.optJSONArray(pid) ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.opt("value")
            if (v is String) out += v
        }
        return out
    }

    private fun itemIds(claims: JSONObject, pid: String): Set<String> {
        val arr = claims.optJSONArray(pid) ?: return emptySet()
        val out = HashSet<String>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id")?.let(out::add)
        }
        return out
    }

    private class Cand(val qid: String, val logo: String?)

    /**
     * [search] = wbsearchentities reply, [entities] = wbgetentities "entities" object.
     * A logo is returned only when exactly ONE item has the same title (exact, normalised), the same kind
     * (film vs TV series) and a compatible year; anything ambiguous or unverified yields [Pick.None].
     */
    fun pick(title: String, kind: Kind, year: Int?, search: JSONObject, entities: JSONObject): Pick {
        if (kind == Kind.Live) return Pick.None("live")
        val want = norm(title)
        if (want.isEmpty()) return Pick.None("empty title")
        val hits = search.optJSONArray("search") ?: return Pick.None("no search hit")
        val cands = ArrayList<Cand>()
        for (i in 0 until minOf(hits.length(), 10)) {
            val h = hits.getJSONObject(i)
            val q = h.optString("id")
            val e = entities.optJSONObject(q) ?: continue
            val names = ArrayList<String>()
            names += h.optString("label"); names += h.optJSONObject("match")?.optString("text").orEmpty()
            e.optJSONObject("labels")?.let { l -> l.keys().forEach { k -> names += l.getJSONObject(k).optString("value") } }
            e.optJSONObject("aliases")?.let { a -> a.keys().forEach { k ->
                val arr = a.getJSONArray(k); for (j in 0 until arr.length()) names += arr.getJSONObject(j).optString("value") } }
            if (names.none { norm(it) == want }) continue
            val claims = e.optJSONObject("claims") ?: continue
            val classes = itemIds(claims, "P31")
            val desc = e.optJSONObject("descriptions")?.let { d -> d.keys().asSequence().joinToString(" ") { d.getJSONObject(it).optString("value") } }.orEmpty().lowercase()
            val isSeries = classes.any { it in SERIES } || SERIES_DESC.containsMatchIn(desc)
            val isFilm = classes.none { it in NOT_FILM } && (classes.any { it in FILM } || FILM_DESC.containsMatchIn(desc)) && !isSeries
            if ((kind == Kind.Movie && !isFilm) || (kind == Kind.Series && !isSeries)) continue
            val ys = years(claims, "P577") + years(claims, "P580")
            if (year != null && ys.isNotEmpty() && ys.none { kotlin.math.abs(it - year) <= 1 }) continue
            cands += Cand(q, strings(claims, "P154").firstOrNull())
        }
        if (cands.isEmpty()) return Pick.None("no verified match")
        if (cands.size > 1) return Pick.None("ambiguous title")
        val c = cands[0]
        val file = c.logo ?: return Pick.None("no logo on Wikidata")
        return if (fileOk(file)) Pick.Found(c.qid, file) else Pick.None("logo file is not svg/png")
    }

    /** Only vector or PNG logos: a JPG cannot be transparent and is usually a photo or poster. */
    fun fileOk(name: String): Boolean = name.lowercase().let { it.endsWith(".svg") || it.endsWith(".png") }

    /**
     * Pixel check of the rendered image ([argb] = w*h pixels): a real title logo is wide-ish, mostly transparent
     * (>= 20% of all pixels and >= 30% of the border) and has a modest amount of opaque ink. Tight-cropped logos
     * whose letters touch the edges still pass; photos, posters and opaque banners (even PNGs with an alpha
     * channel but a solid background) fail.
     */
    fun looksLikeLogo(w: Int, h: Int, argb: IntArray): Boolean {
        if (w <= 0 || h <= 0 || argb.size < w * h) return false
        val ratio = w.toFloat() / h
        if (ratio < 1.15f || ratio > 12f) return false
        var border = 0; var transparentBorder = 0
        for (x in 0 until w) for (y in intArrayOf(0, h - 1)) { border++; if ((argb[y * w + x] ushr 24) < 16) transparentBorder++ }
        for (y in 1 until h - 1) for (x in intArrayOf(0, w - 1)) { border++; if ((argb[y * w + x] ushr 24) < 16) transparentBorder++ }
        if (border == 0 || transparentBorder.toFloat() / border < 0.3f) return false
        var opaque = 0; var clear = 0
        for (p in argb) { val a = p ushr 24; if (a > 128) opaque++ else if (a < 16) clear++ }
        val n = w * h
        return clear.toFloat() / n >= 0.2f && opaque.toFloat() / n in 0.01f..0.9f
    }
}

package com.nabd.tv

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A movie or series that is NOT released yet, from TMDB. [names] = every spelling TMDB knows (normalised), used to spot it in the library. */
data class UpcomingItem(
    val id: Int, val movie: Boolean, val title: String, val names: Set<String>,
    val date: String,                       // yyyy-MM-dd, first release / first air date
    val poster: String?, val backdrop: String?, val overview: String,
    val popularity: Double, val rating: Double?
) {
    val year: Int? get() = date.take(4).toIntOrNull()
}

/** What the detail card adds on top of [UpcomingItem]. */
data class UpcomingDetail(
    val genres: List<String>, val runtimeMin: Int?, val tagline: String, val overview: String,
    val trailerId: String, val cast: List<String>
)

/**
 * "Coming soon": TMDB `discover` (popularity first) for movies and series whose first release is in the next six months.
 * TMDB has no news feed, so this release calendar is the "latest news". Items the library already has are removed in the
 * ViewModel (the title moved from "coming soon" to "available").
 */
object Upcoming {
    private val http by lazy { Net.client(10, 15) }
    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
    private const val AHEAD_DAYS = 180L
    private const val DAY_MS = 86_400_000L

    private fun getObj(url: String): JSONObject =
        http.newCall(Request.Builder().url(url).header("User-Agent", "NabdTV").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            JSONObject(r.body!!.string())
        }

    private fun JSONObject.s(k: String) = if (isNull(k)) "" else optString(k)

    fun ymd(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(ms)

    /** Blocking (call from IO). Two pages in Arabic and in English per kind, so library titles in either language match. */
    fun load(key: String, appLang: String, now: Long = System.currentTimeMillis()): List<UpcomingItem> {
        val from = ymd(now); val to = ymd(now + AHEAD_DAYS * DAY_MS)
        fun kind(movie: Boolean): List<UpcomingItem> {
            val pages = ArrayList<Pair<String, JSONArray>>()
            val range = if (movie) "discover/movie?primary_release_date.gte=$from&primary_release_date.lte=$to"
            else "discover/tv?first_air_date.gte=$from&first_air_date.lte=$to"
            for (lang in listOf("ar", "en-US")) for (page in 1..2) {
                val arr = runCatching {
                    getObj("https://api.themoviedb.org/3/$range&api_key=${Uri.encode(key)}&language=$lang&sort_by=popularity.desc&include_adult=false&page=$page")
                        .optJSONArray("results")
                }.getOrNull() ?: continue
                pages += lang to arr
            }
            return merge(movie, pages, appLang, from)
        }
        val out = kind(true) + kind(false)
        if (out.isEmpty()) throw IOException("TMDB upcoming empty")   // wrong key / offline: the caller keeps the old list
        return out
    }

    /** Joins the Arabic and English answers per TMDB id. Drops past / invalid dates and titles without any picture. */
    internal fun merge(movie: Boolean, pages: List<Pair<String, JSONArray>>, appLang: String, today: String): List<UpcomingItem> {
        class Acc {
            val names = LinkedHashSet<String>(); val titles = HashMap<String, String>(); val overviews = HashMap<String, String>()
            var date = ""; var poster: String? = null; var backdrop: String? = null; var pop = 0.0; var rating: Double? = null
        }
        val byId = LinkedHashMap<Int, Acc>()
        val tf = if (movie) "title" else "name"
        val of = if (movie) "original_title" else "original_name"
        val df = if (movie) "release_date" else "first_air_date"
        for ((lang, arr) in pages) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", -1).takeIf { it > 0 } ?: continue
            val date = o.s(df)
            if (!DATE.matches(date) || date < today) continue
            val a = byId.getOrPut(id) { Acc() }
            a.date = date
            for (f in listOf(tf, of)) {
                val n = LogoMatch.norm(o.s(f))
                if (n.isNotEmpty()) a.names.add(n)
            }
            a.titles[lang] = o.s(tf); a.overviews[lang] = o.s("overview")
            if (a.poster == null) o.s("poster_path").takeIf { it.isNotBlank() }?.let { a.poster = "https://image.tmdb.org/t/p/w500$it" }
            if (a.backdrop == null) o.s("backdrop_path").takeIf { it.isNotBlank() }?.let { a.backdrop = "https://image.tmdb.org/t/p/w780$it" }
            a.pop = maxOf(a.pop, o.optDouble("popularity", 0.0))
            // An unreleased title has almost no votes: a rating from a handful of them would be noise.
            if (a.rating == null && o.optInt("vote_count", 0) >= 10) o.optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.let { a.rating = it }
        }
        val pref = if (appLang == "ar") "ar" else "en-US"
        val other = if (pref == "ar") "en-US" else "ar"
        return byId.mapNotNull { (id, a) ->
            val title = listOf(a.titles[pref], a.titles[other]).firstOrNull { !it.isNullOrBlank() } ?: return@mapNotNull null
            if (a.poster == null && a.backdrop == null) return@mapNotNull null
            val ov = listOf(a.overviews[pref], a.overviews[other]).firstOrNull { !it.isNullOrBlank() } ?: ""
            UpcomingItem(id, movie, title, a.names, a.date, a.poster, a.backdrop, ov, a.pop, a.rating)
        }
    }

    /* ---------------- detail card ---------------- */

    suspend fun detail(key: String, item: UpcomingItem, appLang: String): UpcomingDetail? = withContext(Dispatchers.IO) {
        runCatching {
            val t = if (item.movie) "movie" else "tv"
            val lang = if (appLang == "ar") "ar" else "en-US"
            parseDetail(
                getObj(
                    "https://api.themoviedb.org/3/$t/${item.id}?api_key=${Uri.encode(key)}&language=$lang" +
                        "&append_to_response=videos,credits&include_video_language=${Uri.encode("$appLang,en,null")}"
                )
            )
        }.getOrNull()
    }

    internal fun parseDetail(o: JSONObject): UpcomingDetail {
        val genres = o.optJSONArray("genres")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.s("name")?.takeIf { n -> n.isNotBlank() } }
        } ?: emptyList()
        val runtime = o.optInt("runtime", 0).takeIf { it > 0 } ?: o.optJSONArray("episode_run_time")?.optInt(0, 0)?.takeIf { it > 0 }
        val vids = o.optJSONObject("videos")?.optJSONArray("results")
        val trailer = if (vids == null) "" else
            (0 until vids.length()).mapNotNull { vids.optJSONObject(it) }
                .filter { it.s("site") == "YouTube" && it.s("type") in listOf("Trailer", "Teaser") && it.s("key").isNotBlank() }
                .sortedBy { if (it.s("type") == "Trailer") 0 else 1 }
                .firstOrNull()?.s("key") ?: ""
        val cast = o.optJSONObject("credits")?.optJSONArray("cast")?.let { a ->
            (0 until minOf(a.length(), 8)).mapNotNull { a.optJSONObject(it)?.s("name")?.takeIf { n -> n.isNotBlank() } }
        } ?: emptyList()
        return UpcomingDetail(genres, runtime, o.s("tagline"), o.s("overview"), trailer, cast)
    }

    /* ---------------- dates ---------------- */

    private val TMDB_SIZE = Regex("/t/p/(?:w\\d+|h\\d+|original)/")

    /** Same TMDB picture at another size (e.g. "w780"); any other URL is returned unchanged. */
    internal fun sharper(url: String, size: String): String =
        if ("image.tmdb.org/t/p/" in url) TMDB_SIZE.replace(url, "/t/p/$size/") else url

    internal fun locale(code: String): Locale = if (code == "ar") Locale.forLanguageTag("ar-u-nu-latn") else Locale.ENGLISH

    /** Whole days from today to the release day (0 = today). */
    internal fun daysUntil(date: String, now: Long, tz: TimeZone = TimeZone.getDefault()): Int? =
        NextAiring.midnightOf(date, tz)?.let { NextAiring.dayDiff(it, now, tz) }

    /** "Sat 5 Dec 2026" */
    internal fun dateLabel(date: String, locale: Locale, tz: TimeZone = TimeZone.getDefault()): String? =
        NextAiring.midnightOf(date, tz)?.let { SimpleDateFormat("EEE d MMM yyyy", locale).apply { timeZone = tz }.format(it) }

    /* ---------------- on-device copy, so the rows are there at once on the next launch ---------------- */

    fun save(ctx: Context, l: List<UpcomingItem>, at: Long) {
        runCatching {
            val a = JSONArray()
            l.forEach {
                a.put(
                    JSONObject().put("i", it.id).put("m", it.movie).put("t", it.title).put("n", JSONArray(it.names.toList())).put("d", it.date)
                        .put("p", it.poster ?: "").put("b", it.backdrop ?: "").put("o", it.overview).put("pop", it.popularity).put("r", it.rating ?: 0.0)
                )
            }
            ctx.getSharedPreferences("upcoming", Context.MODE_PRIVATE).edit().putString("d", JSONObject().put("t", at).put("l", a).toString()).apply()
        }
    }

    /** The saved list (past release dates dropped) + when it was fetched, or null. */
    fun loadCached(ctx: Context, now: Long = System.currentTimeMillis()): Pair<List<UpcomingItem>, Long>? = runCatching {
        val s = ctx.getSharedPreferences("upcoming", Context.MODE_PRIVATE).getString("d", null) ?: return null
        val j = JSONObject(s)
        val a = j.optJSONArray("l") ?: return null
        val today = ymd(now)
        val l = (0 until a.length()).mapNotNull { k ->
            val o = a.optJSONObject(k) ?: return@mapNotNull null
            val n = o.optJSONArray("n") ?: return@mapNotNull null
            val date = o.optString("d")
            if (date < today) return@mapNotNull null
            UpcomingItem(
                o.optInt("i"), o.optBoolean("m"), o.optString("t"), (0 until n.length()).map { n.getString(it) }.toSet(), date,
                o.optString("p").ifBlank { null }, o.optString("b").ifBlank { null }, o.optString("o"),
                o.optDouble("pop", 0.0), o.optDouble("r", 0.0).takeIf { it > 0.0 }
            )
        }
        if (l.isEmpty()) null else l to j.optLong("t")
    }.getOrNull()
}

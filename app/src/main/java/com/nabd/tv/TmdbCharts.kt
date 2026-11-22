package com.nabd.tv

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * "Trending" and "Top rated" come ONLY from TMDB (their weekly trending and top_rated charts), never from the
 * provider's own ratings. The charts are matched against the library afterwards by exact title (+ year), so a row
 * only ever contains titles the user can actually play, in TMDB's order.
 */
object TmdbCharts {
    /** One chart entry: every spelling TMDB knows for the title (normalised), plus its release year. */
    class Entry(val names: Set<String>, val year: Int?, val backdrop: String?, val rating: Double?)

    class Charts(
        val trendMovies: List<Entry>,
        val trendSeries: List<Entry>,
        val topMovies: List<Entry>,
        val topSeries: List<Entry>
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    private fun getObj(url: String): JSONObject =
        http.newCall(Request.Builder().url(url).header("User-Agent", "NabdTV").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            JSONObject(r.body!!.string())
        }

    /** Blocking (call from IO). 3 pages (60 titles) per chart, in English and Arabic so both kinds of library titles match. */
    fun load(key: String): Charts {
        fun chart(path: String, movie: Boolean): List<Entry> {
            class Acc(val names: MutableSet<String>, val year: Int?) { var backdrop: String? = null; var rating: Double? = null }
            val byId = LinkedHashMap<Int, Acc>()
            for (lang in listOf("en-US", "ar")) for (page in 1..3) {
                val arr = runCatching {
                    getObj("https://api.themoviedb.org/3/$path?api_key=${Uri.encode(key)}&language=$lang&page=$page").optJSONArray("results")
                }.getOrNull() ?: continue
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    val id = r.optInt("id", -1).takeIf { it > 0 } ?: continue
                    val names = listOf("title", "name", "original_title", "original_name")
                        .map { r.optString(it) }.filter { it.isNotBlank() }.map { LogoMatch.norm(it) }.filter { it.isNotEmpty() }
                    val date = r.optString(if (movie) "release_date" else "first_air_date")
                    val e = byId.getOrPut(id) { Acc(LinkedHashSet(), date.take(4).toIntOrNull()) }
                    e.names.addAll(names)
                    if (e.backdrop == null) r.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }?.let { e.backdrop = "https://image.tmdb.org/t/p/w780$it" }
                    if (e.rating == null) r.optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.let { e.rating = it }
                }
            }
            return byId.values.map { Entry(it.names, it.year, it.backdrop, it.rating) }
        }
        val c = Charts(
            trendMovies = chart("trending/movie/week", true),
            trendSeries = chart("trending/tv/week", false),
            topMovies = chart("movie/top_rated", true),
            topSeries = chart("tv/top_rated", false)
        )
        // A wrong key / no network gives four empty charts: report that as a failure so old results are kept.
        if (c.trendMovies.isEmpty() && c.trendSeries.isEmpty() && c.topMovies.isEmpty() && c.topSeries.isEmpty()) throw IOException("TMDB charts empty")
        return c
    }

    /** Library titles indexed by normalised bare title (provider noise like "AR |", "(2019)", "4K" removed). */
    fun indexOf(l: List<Channel>): Map<String, List<Pair<Channel, Int?>>> {
        val m = HashMap<String, MutableList<Pair<Channel, Int?>>>()
        for (c in l) {
            val (t, y) = LogoMatch.clean(c.name)
            val k = LogoMatch.norm(t)
            if (k.isNotEmpty()) m.getOrPut(k) { ArrayList(1) }.add(c to y)
        }
        return m
    }

    /** Library items for the chart in TMDB order ([ok] filters, e.g. parental rules), max [n], with TMDB artwork per item. */
    fun pick(idx: Map<String, List<Pair<Channel, Int?>>>, entries: List<Entry>, n: Int, ok: (Channel) -> Boolean): Pair<List<Channel>, Map<String, TmdbArt>> {
        val out = ArrayList<Channel>()
        val art = HashMap<String, TmdbArt>()
        val used = HashSet<String>()
        for (e in entries) {
            var hit: Channel? = null
            for (name in e.names) {
                hit = idx[name]?.firstOrNull { (c, y) ->
                    c.url !in used && ok(c) && (e.year == null || y == null || kotlin.math.abs(e.year - y) <= 1)
                }?.first
                if (hit != null) break
            }
            if (hit != null) {
                out += hit; used += hit.url
                e.backdrop?.let { art[hit.url] = TmdbArt(it, e.rating?.let { r -> "%.1f".format(java.util.Locale.US, r) }) }
                if (out.size >= n) break
            }
        }
        return out to art
    }
}

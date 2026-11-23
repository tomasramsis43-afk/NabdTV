package com.nabd.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Opening (intro) window of one episode, in milliseconds. */
data class AniIntro(val startMs: Long, val endMs: Long)

/**
 * Automatic intro detection for anime series.
 *
 * 1. AniList (GraphQL, no key) turns the series title into a MyAnimeList id.
 * 2. AniSkip (community timestamps, no key) returns the opening start / end for that MAL id + episode number.
 *
 * Everything is cached (memory + SharedPreferences) and every failure returns null, so playback is never affected.
 * A manually saved intro end (Store.introEnd) still takes priority in the player.
 */
object AniSkip {
    private const val UA = "NabdTV v2"
    private val JSON = "application/json".toMediaType()

    private val malCache = ConcurrentHashMap<String, Int>()         // "name|season" -> MAL id (0 = no match this session)
    private val introCache = ConcurrentHashMap<String, AniIntro>()  // "mal:ep"
    private val missCache = ConcurrentHashMap.newKeySet<String>()   // "mal:ep" with no timestamps this session

    private const val QUERY =
        "query(\$s:String){Page(perPage:6){media(search:\$s,type:ANIME,format_in:[TV,TV_SHORT,ONA],sort:SEARCH_MATCH)" +
            "{idMal episodes title{romaji english} synonyms}}}"

    suspend fun intro(http: OkHttpClient, ctx: Context, seriesName: String, season: Int?, episode: Int?): AniIntro? =
        withContext(Dispatchers.IO) {
            runCatching {
                val ep = episode?.takeIf { it > 0 } ?: return@runCatching null
                val sn = (season ?: 1).coerceAtLeast(1)
                val base = cleanName(seriesName)
                if (base.length < 2) return@runCatching null
                val prefs = ctx.applicationContext.getSharedPreferences("aniskip", Context.MODE_PRIVATE)

                val malKey = "${norm(base)}|$sn"
                var mal = malCache[malKey] ?: prefs.getInt("mal_$malKey", -1).takeIf { it >= 0 }
                if (mal == null) {
                    mal = findMal(http, base, sn, ep) ?: 0
                    malCache[malKey] = mal
                    if (mal > 0) prefs.edit().putInt("mal_$malKey", mal).apply()
                }
                if (mal <= 0) return@runCatching null

                val key = "$mal:$ep"
                introCache[key]?.let { return@runCatching it }
                if (key in missCache) return@runCatching null
                prefs.getString("op_$key", null)?.split(',')?.takeIf { it.size == 2 }?.let {
                    val s = it[0].toLongOrNull(); val e = it[1].toLongOrNull()
                    if (s != null && e != null) return@runCatching AniIntro(s, e).also { r -> introCache[key] = r }
                }

                val found = fetchSkip(http, mal, ep)
                if (found == null) missCache.add(key)
                else {
                    introCache[key] = found
                    prefs.edit().putString("op_$key", "${found.startMs},${found.endMs}").apply()
                }
                found
            }.getOrNull()
        }

    /* ---------------- AniList: title -> MAL id ---------------- */

    private fun findMal(http: OkHttpClient, base: String, season: Int, episode: Int): Int? {
        val search = if (season > 1) "$base season $season" else base
        val body = JSONObject().put("query", QUERY).put("variables", JSONObject().put("s", search))
            .toString().toRequestBody(JSON)
        val req = Request.Builder().url("https://graphql.anilist.co").header("User-Agent", UA)
            .header("Accept", "application/json").post(body).build()
        val media: JSONArray = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            JSONObject(r.body?.string().orEmpty()).optJSONObject("data")?.optJSONObject("Page")
                ?.optJSONArray("media") ?: return null
        }
        for (i in 0 until media.length()) {
            val m = media.getJSONObject(i)
            val mal = m.optInt("idMal", 0)
            if (mal <= 0) continue
            val titles = buildList {
                m.optJSONObject("title")?.let { t ->
                    add(t.optString("romaji")); add(t.optString("english"))
                }
                m.optJSONArray("synonyms")?.let { a -> for (j in 0 until a.length()) add(a.optString(j)) }
            }.map { norm(it) }.filter { it.isNotEmpty() && it != "null" }
            if (!similar(norm(base), titles)) continue
            val hasSeasonMark = titles.any { SEASON_MARK.containsMatchIn(it) }
            if (season <= 1 && hasSeasonMark) continue                                   // asked for S1, this is S2+
            if (season > 1 && titles.none { seasonMatches(it, season) }) continue        // asked for S2+, wrong entry
            val total = m.optInt("episodes", 0)
            if (total in 1 until episode) continue                                       // episode number cannot exist here
            return mal
        }
        return null
    }

    /* ---------------- AniSkip: MAL id + episode -> opening window ---------------- */

    private fun fetchSkip(http: OkHttpClient, mal: Int, episode: Int): AniIntro? {
        val url = "https://api.aniskip.com/v2/skip-times/$mal/$episode?types=op&types=mixed-op&episodeLength=0"
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null   // 404 = no timestamps for this episode
            val json = JSONObject(r.body?.string().orEmpty())
            if (!json.optBoolean("found", false)) return null
            val arr = json.optJSONArray("results") ?: return null
            var best: AniIntro? = null
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = o.optString("skipType")
                if (type != "op" && type != "mixed-op") continue
                val iv = o.optJSONObject("interval") ?: continue
                val s = (iv.optDouble("startTime", -1.0) * 1000).toLong()
                val e = (iv.optDouble("endTime", -1.0) * 1000).toLong()
                if (s < 0 || e - s !in 15_000L..300_000L) continue   // ignore nonsense windows
                if (best == null || type == "op") best = AniIntro(s, e)
            }
            return best
        }
    }

    /* ---------------- helpers ---------------- */

    private val SEASON_MARK = Regex("\\b(season [2-9]|[2-9](nd|rd|th) season)\\b")

    private fun seasonMatches(title: String, n: Int): Boolean =
        Regex("\\b(season $n|$n(st|nd|rd|th) season)\\b").containsMatchIn(title) || title.endsWith(" $n")

    private fun cleanName(raw: String): String = OpenSubtitles.cleanQuery(raw)
        .replace(Regex("(?i)\\b(season|s)\\s*\\d+\\b"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun norm(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun similar(query: String, candidates: List<String>): Boolean {
        if (query.length < 2) return false
        val qt = query.split(' ').toSet()
        return candidates.any { c ->
            (c.contains(query)) || (c.length >= 3 && query.contains(c)) ||
                c.split(' ').toSet().let { ct -> ct.intersect(qt).size.toFloat() / ct.union(qt).size >= 0.5f }
        }
    }
}

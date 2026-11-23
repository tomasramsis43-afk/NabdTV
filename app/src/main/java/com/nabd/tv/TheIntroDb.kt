package com.nabd.tv

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Intro timestamps for movies and (non-anime) series from TheIntroDB (community database, keyed by TMDB id).
 * Reading needs no account. The title -> TMDB id step needs the user's TMDB key (Settings) and accepts a result only
 * when its title equals the provider title, so a window is never attached to the wrong title.
 *
 * Every failure returns null. The result reuses [AniIntro]; a manually saved intro end still wins in the player.
 */
object TheIntroDb {
    private const val UA = "NabdTV v2"

    private val idCache = ConcurrentHashMap<String, Int>()               // "tv|title" / "movie|title" -> TMDB id (0 = no match)
    private val introCache = ConcurrentHashMap<String, AniIntro>()
    private val missCache = ConcurrentHashMap.newKeySet<String>()

    suspend fun intro(
        http: OkHttpClient, ctx: Context, tmdbKey: String,
        title: String, series: Boolean, season: Int?, episode: Int?
    ): AniIntro? = withContext(Dispatchers.IO) {
        runCatching {
            if (tmdbKey.isBlank()) return@runCatching null
            if (series && ((season ?: 0) <= 0 || (episode ?: 0) <= 0)) return@runCatching null
            val clean = title.replace(Regex("[\\[(].*?[\\])]"), " ").replace(Regex("\\s+"), " ").trim()
            val want = norm(clean)
            if (want.isEmpty()) return@runCatching null
            val prefs = ctx.applicationContext.getSharedPreferences("theintrodb", Context.MODE_PRIVATE)

            val kind = if (series) "tv" else "movie"
            val idKey = "$kind|$want"
            var id = idCache[idKey] ?: prefs.getInt("id_$idKey", -1).takeIf { it >= 0 }
            if (id == null) {
                id = findTmdbId(http, tmdbKey, kind, clean, want) ?: return@runCatching null   // request failed: retry later
                idCache[idKey] = id
                if (id > 0) prefs.edit().putInt("id_$idKey", id).apply()
            }
            if (id <= 0) return@runCatching null

            val key = if (series) "$id:$season:$episode" else "$id"
            introCache[key]?.let { return@runCatching it }
            if (key in missCache) return@runCatching null
            prefs.getString("op_$key", null)?.split(',')?.takeIf { it.size == 2 }?.let {
                val s = it[0].toLongOrNull(); val e = it[1].toLongOrNull()
                if (s != null && e != null) return@runCatching AniIntro(s, e).also { r -> introCache[key] = r }
            }

            val found = fetch(http, id, if (series) season else null, if (series) episode else null)
            if (found == null) missCache.add(key)
            else {
                introCache[key] = found
                prefs.edit().putString("op_$key", "${found.startMs},${found.endMs}").apply()
            }
            found
        }.getOrNull()
    }

    /** TMDB id for an exact title match; 0 = confirmed no match; null = the request failed (do not cache). */
    private fun findTmdbId(http: OkHttpClient, tmdbKey: String, kind: String, clean: String, want: String): Int? {
        val url = "https://api.themoviedb.org/3/search/$kind?api_key=${Uri.encode(tmdbKey)}&query=${Uri.encode(clean)}"
        val res: JSONArray = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            JSONObject(r.body?.string().orEmpty()).optJSONArray("results") ?: return 0
        }
        for (i in 0 until minOf(res.length(), 5)) {
            val o = res.getJSONObject(i)
            val names = listOf(str(o, "title"), str(o, "name"), str(o, "original_title"), str(o, "original_name"))
            if (names.any { norm(it) == want }) return o.optInt("id")
        }
        return 0
    }

    /**
     * GET /v2/media?tmdb_id=..[&season=..&episode=..]. The intro list holds objects with start_ms / end_ms;
     * start_ms is null when the intro starts at the very beginning. Field names are read defensively.
     */
    private fun fetch(http: OkHttpClient, tmdbId: Int, season: Int?, episode: Int?): AniIntro? {
        val b = StringBuilder("https://api.theintrodb.org/v2/media?tmdb_id=$tmdbId")
        if (season != null && episode != null) b.append("&season=$season&episode=$episode")
        val req = Request.Builder().url(b.toString()).header("User-Agent", UA).header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null   // 404 = nothing known for this title / episode
            val root = JSONObject(r.body?.string().orEmpty())
            val arr = (root.optJSONArray("intro") ?: root.optJSONObject("data")?.optJSONArray("intro")) ?: return null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = ms(o, "start_ms", "startMs") ?: 0L
                val e = ms(o, "end_ms", "endMs") ?: continue
                if (s >= 0 && e - s in 5_000L..400_000L) return AniIntro(s, e)
            }
            return null
        }
    }

    private fun ms(o: JSONObject, vararg keys: String): Long? {
        for (k in keys) if (o.has(k) && !o.isNull(k)) return o.optDouble(k, Double.NaN).takeIf { !it.isNaN() }?.toLong()
        return null
    }

    private fun str(o: JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k)
    private fun norm(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
}

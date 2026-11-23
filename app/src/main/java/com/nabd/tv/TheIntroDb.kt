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
 * Every failure returns an empty list. The result reuses [AniIntro] (intro / recap / credits windows); a manually saved
 * intro end still wins in the player.
 */
object TheIntroDb {
    private const val UA = "NabdTV v2"

    private val idCache = ConcurrentHashMap<String, Int>()               // "tv|title" / "movie|title" -> TMDB id (0 = no match)
    private val introCache = ConcurrentHashMap<String, List<AniIntro>>()
    private val missCache = ConcurrentHashMap.newKeySet<String>()

    suspend fun segments(
        http: OkHttpClient, ctx: Context, tmdbKey: String,
        title: String, series: Boolean, season: Int?, episode: Int?
    ): List<AniIntro> = withContext(Dispatchers.IO) {
        val none = emptyList<AniIntro>()
        runCatching {
            if (tmdbKey.isBlank()) return@runCatching none
            if (series && ((season ?: 0) <= 0 || (episode ?: 0) <= 0)) return@runCatching none
            val clean = title.replace(Regex("[\\[(].*?[\\])]"), " ").replace(Regex("\\s+"), " ").trim()
            val want = norm(clean)
            if (want.isEmpty()) return@runCatching none
            val prefs = ctx.applicationContext.getSharedPreferences("theintrodb", Context.MODE_PRIVATE)

            val kind = if (series) "tv" else "movie"
            val idKey = "$kind|$want"
            var id = idCache[idKey] ?: prefs.getInt("id_$idKey", -1).takeIf { it >= 0 }
            if (id == null) {
                id = findTmdbId(http, tmdbKey, kind, clean, want) ?: return@runCatching none   // request failed: retry later
                idCache[idKey] = id
                if (id > 0) prefs.edit().putInt("id_$idKey", id).apply()
            }
            if (id <= 0) return@runCatching none

            val key = if (series) "$id:$season:$episode" else "$id"
            introCache[key]?.let { return@runCatching it }
            if (key in missCache) return@runCatching none
            prefs.getString("sg_$key", null)?.let { AniIntro.unpackAll(it) }?.takeIf { it.isNotEmpty() }
                ?.let { return@runCatching it.also { r -> introCache[key] = r } }

            val found = fetch(http, id, if (series) season else null, if (series) episode else null)
            if (found.isEmpty()) missCache.add(key)
            else {
                introCache[key] = found
                prefs.edit().putString("sg_$key", AniIntro.packAll(found)).apply()
            }
            found
        }.getOrDefault(emptyList())
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
     * GET /v2/media?tmdb_id=..[&season=..&episode=..]. The intro / recap / credits lists hold objects with start_ms / end_ms;
     * start_ms is null when the segment starts at the very beginning and end_ms is null when it runs to the end of the media.
     * Field names are read defensively.
     */
    private fun fetch(http: OkHttpClient, tmdbId: Int, season: Int?, episode: Int?): List<AniIntro> {
        val b = StringBuilder("https://api.theintrodb.org/v2/media?tmdb_id=$tmdbId")
        if (season != null && episode != null) b.append("&season=$season&episode=$episode")
        val req = Request.Builder().url(b.toString()).header("User-Agent", UA).header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return emptyList()   // 404 = nothing known for this title / episode
            val root0 = JSONObject(r.body?.string().orEmpty())
            val root = root0.optJSONObject("data") ?: root0
            val out = ArrayList<AniIntro>()
            for ((field, kind) in listOf("intro" to SkipKind.Intro, "recap" to SkipKind.Recap, "credits" to SkipKind.Credits)) {
                val arr = root.optJSONArray(field) ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val rawStart = ms(o, "start_ms", "startMs")
                    val rawEnd = ms(o, "end_ms", "endMs")
                    val s = rawStart ?: 0L
                    when (kind) {
                        SkipKind.Credits -> {
                            // Credits need a real start; no end = runs to the end of the media.
                            if (rawStart == null || rawStart <= 0L) continue
                            if (rawEnd != null && rawEnd - s !in 10_000L..900_000L) continue
                            out.add(AniIntro(s, rawEnd ?: -1L, kind)); break
                        }
                        else -> {
                            val e = rawEnd ?: continue
                            val ok = if (kind == SkipKind.Intro) e - s in 5_000L..400_000L else e - s in 5_000L..300_000L
                            if (s >= 0 && ok) { out.add(AniIntro(s, e, kind)); break }
                        }
                    }
                }
            }
            return out.sortedBy { it.startMs }
        }
    }

    private fun ms(o: JSONObject, vararg keys: String): Long? {
        for (k in keys) if (o.has(k) && !o.isNull(k)) return o.optDouble(k, Double.NaN).takeIf { !it.isNaN() }?.toLong()
        return null
    }

    private fun str(o: JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k)
    private fun norm(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
}

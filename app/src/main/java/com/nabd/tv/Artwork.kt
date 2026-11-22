package com.nabd.tv

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One verified picture: where it lives, which source gave it and under which terms it may be shown. */
data class Art(val url: String, val source: String, val license: String)

/**
 * Central finder for posters and backdrops (title logos stay in [Logos]; episode stills in Repo).
 *
 * Sources, in order:
 *  - TMDB, only when the user entered a key in Settings (the title must match exactly after [LogoMatch.norm] and the year must agree);
 *  - TVmaze (no key) for series posters.
 * Rules that keep image types apart: a poster must be portrait, a backdrop landscape; a result that fails is never substituted
 * by another type. Answers (url + source + licence) are stored in filesDir/artwork/index.json so the lookup is not repeated;
 * the pictures themselves are cached on disk by Coil. "Nothing found" is retried after [NEG_TTL]; offline failures are not cached.
 */
object Artwork {
    private const val UA = "NabdTV/2.0 (+https://github.com/tomasramsis43-afk/NabdTV)"
    private const val NEG_TTL = 7L * 24 * 3600 * 1000
    private const val TMDB_LICENSE = "TMDB (free for use with attribution; images belong to their rights holders)"
    private const val TVMAZE_LICENSE = "TVmaze (CC BY-SA)"

    @Volatile var tmdbKey: () -> String = { "" }
    private var dir: File? = null
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(3)
    private val inflight = ConcurrentHashMap<String, Deferred<Art?>>()
    private val mem = ConcurrentHashMap<String, Art>()
    private val index = JSONObject()
    @Volatile private var loaded = false

    fun init(ctx: Context) { dir = File(ctx.applicationContext.filesDir, "artwork").also { it.mkdirs() } }

    /** Portrait poster (about 2:3) for a movie or series, or null — callers keep the provider image as fallback. */
    suspend fun poster(title: String, kind: Kind, year: Int?): Art? = find("poster", title, kind, year)

    /** Wide backdrop (landscape) for a movie or series, or null. A poster is never stretched into a backdrop. */
    suspend fun backdrop(title: String, kind: Kind, year: Int?): Art? = find("backdrop", title, kind, year)

    private suspend fun find(type: String, rawTitle: String, kind: Kind, yearHint: Int?): Art? {
        val d = dir ?: return null
        if (kind == Kind.Live) return null
        val (title, y) = LogoMatch.clean(rawTitle)
        if (title.isBlank()) return null
        val year = yearHint ?: y
        val k = "$type:${kind.name}:${LogoMatch.norm(title)}:${year ?: 0}" + if (tmdbKey().isNotBlank()) ":t" else ""
        mem[k]?.let { return it }
        return inflight.getOrPut(k) {
            scope.async { try { gate.withPermit { resolve(d, k, type, title, kind, year) } } finally { inflight.remove(k) } }
        }.await()
    }

    private fun resolve(d: File, k: String, type: String, title: String, kind: Kind, year: Int?): Art? {
        ensureLoaded(d)
        synchronized(index) { index.optJSONObject(k) }?.let { e ->
            if (e.optString("s") == "ok") {
                val a = Art(e.optString("u"), e.optString("src"), e.optString("lic")); mem[k] = a; return a
            } else if (System.currentTimeMillis() - e.optLong("t") < NEG_TTL) return null
        }
        return try {
            val art = (if (tmdbKey().isNotBlank()) tmdb(type, title, kind, year) else null)
                ?: (if (type == "poster" && kind == Kind.Series) tvmaze(title, year) else null)
            synchronized(index) {
                index.put(k, if (art == null) JSONObject().put("s", "none").put("t", System.currentTimeMillis())
                else JSONObject().put("s", "ok").put("u", art.url).put("src", art.source).put("lic", art.license).put("t", System.currentTimeMillis()))
            }
            save(d)
            art?.also { mem[k] = it }
        } catch (_: IOException) { null }        // offline: not cached
          catch (_: Exception) { null }
    }

    private fun tmdb(type: String, title: String, kind: Kind, year: Int?): Art? {
        val key = tmdbKey().ifBlank { return null }
        val t = if (kind == Kind.Series) "tv" else "movie"
        val want = LogoMatch.norm(title)
        val res = getObj("https://api.themoviedb.org/3/search/$t?api_key=${Uri.encode(key)}&language=${if (LogoMatch.hasArabic(title)) "ar" else "en"}&query=${Uri.encode(title)}")
            .optJSONArray("results") ?: return null
        var hit: JSONObject? = null
        for (i in 0 until minOf(res.length(), 8)) {
            val r = res.getJSONObject(i)
            val names = listOf(r.optString("title"), r.optString("name"), r.optString("original_title"), r.optString("original_name"))
            if (names.none { it.isNotBlank() && LogoMatch.norm(it) == want }) continue
            val date = r.optString("release_date").ifBlank { r.optString("first_air_date") }
            val ry = date.take(4).toIntOrNull()
            if (year != null && ry != null && kotlin.math.abs(ry - year) > 1) continue
            hit = r; break
        }
        hit ?: return null
        if (type == "poster") {
            val p = hit.optString("poster_path")
            return if (p.isBlank()) null else Art("https://image.tmdb.org/t/p/w500$p", "tmdb", TMDB_LICENSE)
        }
        // Backdrops: landscape only, neutral (no text) first, then by votes.
        val id = hit.optInt("id")
        val arr = getObj("https://api.themoviedb.org/3/$t/$id/images?api_key=${Uri.encode(key)}&include_image_language=null")
            .optJSONArray("backdrops")
        var best: JSONObject? = null; var bestScore = Double.NEGATIVE_INFINITY
        if (arr != null) for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            val w = b.optInt("width"); val h = b.optInt("height")
            if (h <= 0 || w.toDouble() / h < 1.6 || w < 1000) continue
            val score = b.optDouble("vote_average", 0.0) * 10 + minOf(w, 3000) / 1000.0
            if (score > bestScore) { bestScore = score; best = b }
        }
        val p = best?.optString("file_path")?.takeIf { it.isNotBlank() }
            ?: hit.optString("backdrop_path").takeIf { it.isNotBlank() } ?: return null
        return Art("https://image.tmdb.org/t/p/w1280$p", "tmdb", TMDB_LICENSE)
    }

    private fun tvmaze(title: String, year: Int?): Art? {
        val want = LogoMatch.norm(title)
        val res = JSONArray(getText("https://api.tvmaze.com/search/shows?q=${Uri.encode(title)}"))
        for (i in 0 until minOf(res.length(), 5)) {
            val sh = res.getJSONObject(i).optJSONObject("show") ?: continue
            if (LogoMatch.norm(sh.optString("name")) != want) continue
            val ry = sh.optString("premiered").take(4).toIntOrNull()
            if (year != null && ry != null && kotlin.math.abs(ry - year) > 1) continue
            val u = sh.optJSONObject("image")?.optString("original").orEmpty().replace("http://", "https://")
            if (u.isNotBlank()) return Art(u, "tvmaze", TVMAZE_LICENSE)
        }
        return null
    }

    private fun getText(url: String): String =
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body!!.string()
        }

    private fun getObj(url: String) = JSONObject(getText(url))

    private fun ensureLoaded(d: File) {
        if (loaded) return
        synchronized(index) {
            if (loaded) return
            runCatching { File(d, "index.json").takeIf { it.isFile }?.let { f ->
                val j = JSONObject(f.readText()); j.keys().forEach { index.put(it, j.get(it)) } } }
            loaded = true
        }
    }

    private fun save(d: File) {
        val text = synchronized(index) { index.toString() }
        runCatching { File(d, "index.json").writeText(text) }
    }
}

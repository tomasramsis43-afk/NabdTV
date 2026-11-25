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
    private val http = Net.client(10, 12, redirects = true)
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
        val k = "$type${if (type == "poster") "3" else "2"}:${kind.name}:${LogoMatch.norm(title)}:${year ?: 0}" + if (tmdbKey().isNotBlank()) ":t" else ""
        mem[k]?.let { return it }
        return inflight.getOrPut(k) {
            scope.async { try { gate.withPermit { resolve(d, k, type, title, kind, year) } } finally { inflight.remove(k) } }
        }.await()
    }

    private fun resolve(d: File, k: String, type: String, title: String, kind: Kind, year: Int?): Art? {
        ensureLoaded(d)
        if (mem.size > 1000) mem.clear()
        // Bound the in-memory/persisted index so it cannot grow without limit across thousands of titles.
        if (synchronized(index) { index.length() } > 5000) synchronized(index) {
            val keys = index.keys().asSequence().toList(); keys.forEach { index.remove(it) }
        }
        synchronized(index) { index.optJSONObject(k) }?.let { e ->
            if (e.optString("s") == "ok") {
                val a = Art(e.optString("u"), e.optString("src"), e.optString("lic")); mem[k] = a; return a
            } else if (System.currentTimeMillis() - e.optLong("t") < NEG_TTL) return null
        }
        return try {
            val art = (if (tmdbKey().isNotBlank()) tmdb(type, title, kind, year) else null)
                ?: (if (type == "poster" && kind == Kind.Series && tmdbKey().isBlank()) tvmaze(title, year) else null)   // with a TMDB key a missing textless poster is not replaced by a titled one
            synchronized(index) {
                index.put(k, if (art == null) JSONObject().put("s", "none").put("t", System.currentTimeMillis())
                else JSONObject().put("s", "ok").put("u", art.url).put("src", art.source).put("lic", art.license).put("t", System.currentTimeMillis()))
            }
            save(d)
            art?.also { mem[k] = it }
        } catch (e: IOException) { null }        // offline: not cached
          catch (e: Exception) { Diag.note("Artwork.resolve", e); null }
    }

    /** Cast (with photos) and a YouTube trailer id for a verified TMDB match; null without a key or match. */
    suspend fun extras(rawTitle: String, kind: Kind, yearHint: Int?): Extras? {
        val d = dir ?: return null
        if (kind == Kind.Live || tmdbKey().isBlank()) return null
        val (title, y) = LogoMatch.clean(rawTitle)
        if (title.isBlank()) return null
        val year = yearHint ?: y
        val k = "extras:${kind.name}:${LogoMatch.norm(title)}:${year ?: 0}"
        xmem[k]?.let { return it.takeIf { e -> e !== NO_EXTRAS } }
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            gate.withPermit {
                ensureLoaded(d)
                synchronized(index) { index.optJSONObject(k) }?.let { e ->
                    // A cached answer without a trailer is looked up again after a few hours (TMDB gains videos over time).
                    val stale = e.optString("trailer").isBlank() && System.currentTimeMillis() - e.optLong("t") > 6L * 3600 * 1000
                    if (e.optString("s") == "ok" && !stale) {
                        val ar = e.optJSONArray("cast") ?: JSONArray()
                        val people = (0 until ar.length()).map { i -> ar.getJSONObject(i).let { Person(it.optString("n"), it.optString("p").ifBlank { null }, it.optString("r"), it.optInt("i")) } }
                        return@withPermit Extras(people, e.optString("trailer")).also { xmem[k] = it }
                    } else if (e.optString("s") != "ok" && System.currentTimeMillis() - e.optLong("t") < NEG_TTL) return@withPermit null
                }
                try {
                    val key = tmdbKey()
                    val t = if (kind == Kind.Series) "tv" else "movie"
                    val hit = tmdbMatch(key, title, kind, year)
                    if (hit == null) {
                        synchronized(index) { index.put(k, JSONObject().put("s", "none").put("t", System.currentTimeMillis())) }; save(d)
                        xmem[k] = NO_EXTRAS; return@withPermit null
                    }
                    val id = hit.optInt("id")
                    val lang = if (LogoMatch.hasArabic(title)) "ar" else "en"
                    val credits = getObj("https://api.themoviedb.org/3/$t/$id/credits?api_key=${Uri.encode(key)}&language=$lang").optJSONArray("cast")
                    val people = ArrayList<Person>()
                    if (credits != null) for (i in 0 until minOf(credits.length(), 15)) {
                        val c = credits.getJSONObject(i)
                        val n = c.optString("name"); if (n.isBlank()) continue
                        val pp = c.optString("profile_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w185$it" }
                        people += Person(n, pp, c.optString("character"), c.optInt("id"))
                    }
                    val vids = getObj("https://api.themoviedb.org/3/$t/$id/videos?api_key=${Uri.encode(key)}&include_video_language=$lang,en,null").optJSONArray("results")
                    var trailer = ""
                    if (vids != null) {
                        val cands = (0 until vids.length()).map { vids.getJSONObject(it) }
                            .filter { it.optString("site") == "YouTube" && it.optString("type") in listOf("Trailer", "Teaser") && it.optString("key").isNotBlank() }
                            .sortedBy { if (it.optString("type") == "Trailer") 0 else 1 }
                        trailer = (cands.firstOrNull { it.optString("iso_639_1") == lang && it.optBoolean("official") }
                            ?: cands.firstOrNull { it.optString("iso_639_1") == lang } ?: cands.firstOrNull { it.optBoolean("official") } ?: cands.firstOrNull())
                            ?.optString("key").orEmpty()
                    }
                    val ex = Extras(people, trailer)
                    val arr = JSONArray(); people.forEach { arr.put(JSONObject().put("n", it.name).put("p", it.photo ?: "").put("r", it.role).put("i", it.id)) }
                    synchronized(index) { index.put(k, JSONObject().put("s", "ok").put("cast", arr).put("trailer", trailer).put("t", System.currentTimeMillis())) }
                    save(d); xmem[k] = ex; ex
                } catch (_: Exception) { null }
            }
        }
    }

    data class Extras(val people: List<Person>, val trailer: String)

    private val fmem = ConcurrentHashMap<Int, Set<String>>()

    /** Normalised titles (movies and series) an actor appeared in, from TMDB; empty without a key or on failure. */
    suspend fun filmography(personId: Int): Set<String> {
        val d = dir ?: return emptySet()
        if (personId <= 0 || tmdbKey().isBlank()) return emptySet()
        fmem[personId]?.let { return it }
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            gate.withPermit {
                ensureLoaded(d)
                val k = "person:$personId"
                synchronized(index) { index.optJSONObject(k) }?.let { e ->
                    val a = e.optJSONArray("t") ?: JSONArray()
                    return@withPermit (0 until a.length()).map { a.getString(it) }.toSet().also { fmem[personId] = it }
                }
                try {
                    val arr = getObj("https://api.themoviedb.org/3/person/$personId/combined_credits?api_key=${Uri.encode(tmdbKey())}&language=ar").optJSONArray("cast")
                    val out = LinkedHashSet<String>()
                    if (arr != null) for (i in 0 until arr.length()) {
                        val c = arr.getJSONObject(i)
                        if (c.optString("media_type") !in listOf("movie", "tv")) continue
                        for (n in listOf("title", "name", "original_title", "original_name")) c.optString(n).takeIf { it.isNotBlank() }?.let { out += LogoMatch.norm(it) }
                    }
                    out.remove("")
                    synchronized(index) { index.put(k, JSONObject().put("t", JSONArray(out.toList())).put("time", System.currentTimeMillis())) }
                    save(d); fmem[personId] = out; out
                } catch (_: Exception) { emptySet() }
            }
        }
    }
    private val rmem = ConcurrentHashMap<String, Set<String>>()
    private const val RECS_TTL = 14L * 24 * 3600 * 1000

    /** Normalised titles TMDB recommends for the verified match of this title; empty without a key, a match, or on failure. */
    suspend fun recommended(rawTitle: String, kind: Kind, yearHint: Int?): Set<String> {
        val d = dir ?: return emptySet()
        if (kind == Kind.Live || tmdbKey().isBlank()) return emptySet()
        val (title, y) = LogoMatch.clean(rawTitle)
        if (title.isBlank()) return emptySet()
        val year = yearHint ?: y
        val k = "recs:${kind.name}:${LogoMatch.norm(title)}:${year ?: 0}"
        rmem[k]?.let { return it }
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            gate.withPermit {
                ensureLoaded(d)
                synchronized(index) { index.optJSONObject(k) }?.let { e ->
                    if (System.currentTimeMillis() - e.optLong("time") < RECS_TTL) {
                        val a = e.optJSONArray("t") ?: JSONArray()
                        return@withPermit (0 until a.length()).map { a.getString(it) }.toSet().also { rmem[k] = it }
                    }
                }
                try {
                    val key = tmdbKey()
                    val t = if (kind == Kind.Series) "tv" else "movie"
                    val out = LinkedHashSet<String>()
                    val hit = tmdbMatch(key, title, kind, year)
                    if (hit != null) {
                        val lang = if (LogoMatch.hasArabic(title)) "ar" else "en"
                        val arr = getObj("https://api.themoviedb.org/3/$t/${hit.optInt("id")}/recommendations?api_key=${Uri.encode(key)}&language=$lang").optJSONArray("results")
                        if (arr != null) for (i in 0 until minOf(arr.length(), 20)) {
                            val r = arr.getJSONObject(i)
                            for (n in listOf("title", "name", "original_title", "original_name")) r.optString(n).takeIf { it.isNotBlank() }?.let { out += LogoMatch.norm(it) }
                        }
                        out.remove("")
                    }
                    synchronized(index) { index.put(k, JSONObject().put("t", JSONArray(out.toList())).put("time", System.currentTimeMillis())) }
                    save(d); rmem[k] = out; out
                } catch (_: Exception) { emptySet<String>() }
            }
        }
    }
    private val NO_EXTRAS = Extras(emptyList(), "")
    private val xmem = ConcurrentHashMap<String, Extras>()

    /** Exact TMDB match for a title (overview, year, rating); null without a key or without a verified match. Blocking: call off the main thread. */
    fun hit(rawTitle: String, kind: Kind, yearHint: Int?): JSONObject? {
        val key = tmdbKey().ifBlank { return null }
        if (kind == Kind.Live) return null
        val (title, y) = LogoMatch.clean(rawTitle)
        if (title.isBlank()) return null
        return runCatching { tmdbMatch(key, title, kind, yearHint ?: y) }.getOrNull()
    }

    /** First TMDB search result whose title equals [title] exactly (after normalising) and whose year agrees. */
    private fun tmdbMatch(key: String, title: String, kind: Kind, year: Int?): JSONObject? {
        val t = if (kind == Kind.Series) "tv" else "movie"
        val want = LogoMatch.norm(title)
        val res = getObj("https://api.themoviedb.org/3/search/$t?api_key=${Uri.encode(key)}&language=${if (LogoMatch.hasArabic(title)) "ar" else "en"}&query=${Uri.encode(title)}")
            .optJSONArray("results") ?: return null
        for (i in 0 until minOf(res.length(), 8)) {
            val r = res.getJSONObject(i)
            val names = listOf(r.optString("title"), r.optString("name"), r.optString("original_title"), r.optString("original_name"))
            if (names.none { it.isNotBlank() && LogoMatch.norm(it) == want }) continue
            val date = r.optString("release_date").ifBlank { r.optString("first_air_date") }
            val ry = date.take(4).toIntOrNull()
            if (year != null && ry != null && kotlin.math.abs(ry - year) > 1) continue
            return r
        }
        return null
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
            // Textless posters only (no title printed on the picture): the best-rated PORTRAIT one without a language.
            // When TMDB has none, nothing is returned here and the card keeps the picture it already has.
            val id0 = hit.optInt("id")
            val posters = runCatching {
                getObj("https://api.themoviedb.org/3/$t/$id0/images?api_key=${Uri.encode(key)}&include_image_language=null").optJSONArray("posters")
            }.getOrNull()
            var bestP: JSONObject? = null; var bestScore = Double.NEGATIVE_INFINITY
            if (posters != null) for (i in 0 until posters.length()) {
                val b = posters.getJSONObject(i)
                val w = b.optInt("width"); val h = b.optInt("height")
                if (h <= 0 || w.toDouble() / h > 0.8 || w < 400) continue     // portrait only
                if (!b.isNull("iso_639_1") && b.optString("iso_639_1").isNotBlank()) continue   // has text in some language
                val score = b.optDouble("vote_average", 0.0) * 5 + minOf(w, 2000) / 1000.0
                if (score > bestScore) { bestScore = score; bestP = b }
            }
            val p = bestP?.optString("file_path")?.takeIf { it.isNotBlank() } ?: return null
            return Art("https://image.tmdb.org/t/p/w780$p", "tmdb", TMDB_LICENSE)
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

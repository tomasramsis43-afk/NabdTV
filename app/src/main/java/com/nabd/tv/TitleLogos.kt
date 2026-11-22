package com.nabd.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Official title logos (transparent PNG) for movies and series — no account, no API key.
 *
 * Resolution order for a title:
 *  1. Manual logo supplied by the user/developer (see docs/LOGOS.md):
 *       filesDir/logos/manual/<normalised title>.png        (local asset)
 *       filesDir/logos/manual/manual.json  {"Title": "https://…/logo.png"}   (optional URL)
 *  2. Persistent cache (found logos are kept forever, "no logo" answers are retried after 14 days).
 *  3. Wikidata "logo image" (P154) -> Wikimedia Commons, accepted only when [LogoMatch] verifies the title,
 *     kind and year and the rendered picture is a transparent logo.
 * Nothing is shown (the caller falls back to the plain title) when no verified logo exists.
 */
object Logos {
    private const val UA = "NabdTV/2.0 (+https://github.com/tomasramsis43-afk/NabdTV)"
    private const val NEG_TTL = 14L * 24 * 3600 * 1000

    @Volatile var enabled = true
    /** Optional TMDB key entered by the user in Settings. Blank = TMDB is never contacted. */
    @Volatile var tmdbKey: () -> String = { "" }
    private var dir: File? = null
    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val inflight = ConcurrentHashMap<String, Deferred<File?>>()
    private val mem = ConcurrentHashMap<String, File>()      // verified logos only
    private val index = JSONObject()                         // key -> {s:"ok"|"none", f:file, t:time}
    private var loaded = false

    fun init(ctx: Context) {
        dir = File(ctx.applicationContext.filesDir, "logos").also { it.mkdirs(); File(it, "manual").mkdirs() }
    }

    // The key changes when a TMDB key is added, so earlier "no logo" answers do not hide what TMDB can find.
    private fun key(kind: Kind, title: String, year: Int?) =
        "${kind.name}:${LogoMatch.norm(title)}:${year ?: 0}" + if (tmdbKey().isNotBlank()) ":t" else ""

    /** Instant, non-blocking lookup (memory only). */
    fun cached(title: String, kind: Kind, year: Int?): File? {
        val (t, y) = LogoMatch.clean(title)
        return mem[key(kind, t, year ?: y)]?.takeIf { it.exists() }
    }

    /** The verified logo file for a title, or null (never throws, never blocks the UI thread). */
    suspend fun get(title: String, kind: Kind, year: Int?): File? {
        val d = dir ?: return null
        if (!enabled || kind == Kind.Live) return null
        val (t, y) = LogoMatch.clean(title)
        if (t.isBlank()) return null
        val yr = year ?: y
        val k = key(kind, t, yr)
        mem[k]?.takeIf { it.exists() }?.let { return it }
        val job = inflight.getOrPut(k) {
            scope.async { try { gate.withPermit { resolve(d, k, t, kind, yr) } } finally { inflight.remove(k) } }
        }
        return job.await()
    }

    private fun resolve(d: File, k: String, title: String, kind: Kind, year: Int?): File? {
        ensureLoaded(d)
        manual(d, title)?.let { mem[k] = it; return it }
        synchronized(index) { index.optJSONObject(k) }?.let { e ->
            if (e.optString("s") == "ok") {
                val f = File(d, e.optString("f")); if (f.exists()) { mem[k] = f; return f }
            } else if (System.currentTimeMillis() - e.optLong("t") < NEG_TTL) return null
        }
        if (tmdbKey().isNotBlank()) {
            try {
                tmdbLogo(d, k, title, kind, year)?.let { return it }
            } catch (_: Exception) { /* fall through to Wikidata */ }
        }
        return try {
            val lang = if (LogoMatch.hasArabic(title)) "ar" else "en"
            val search = getJson("https://www.wikidata.org/w/api.php?action=wbsearchentities&type=item&limit=10&format=json" +
                "&language=$lang&uselang=$lang&search=${Uri.encode(title)}")
            val ids = search.optJSONArray("search")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("id") } }.orEmpty()
            if (ids.isEmpty()) return none(d, k)
            val ents = getJson("https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=claims|labels|aliases|descriptions" +
                "&languages=en|ar|mul&ids=${ids.joinToString("|")}").optJSONObject("entities") ?: return none(d, k)
            when (val p = LogoMatch.pick(title, kind, year, search, ents)) {
                is LogoMatch.Pick.None -> none(d, k)
                is LogoMatch.Pick.Found -> {
                    val bytes = download("https://commons.wikimedia.org/wiki/Special:FilePath/${Uri.encode(p.file.replace(' ', '_'))}?width=600")
                    if (bytes == null || !verifyBitmap(bytes)) none(d, k) else {
                        val f = File(d, sha(k) + ".png"); f.writeBytes(bytes)
                        synchronized(index) { index.put(k, JSONObject().put("s", "ok").put("f", f.name).put("q", p.qid).put("t", System.currentTimeMillis())) }
                        save(d); mem[k] = f; f
                    }
                }
            }
        } catch (_: IOException) { null }          // offline / timeout: do not cache a negative answer
          catch (_: Exception) { null }
    }

    /**
     * TMDB logo (transparent PNG). The title must match exactly after normalising (Arabic or original title) and the year
     * must agree when known; the logo language prefers the title's own language, then neutral, then English.
     */
    private fun tmdbLogo(d: File, k: String, title: String, kind: Kind, year: Int?): File? {
        val key = tmdbKey().ifBlank { return null }
        val type = if (kind == Kind.Series) "tv" else "movie"
        val want = LogoMatch.norm(title)
        val ar = LogoMatch.hasArabic(title)
        val res = getJson("https://api.themoviedb.org/3/search/$type?api_key=${Uri.encode(key)}&language=${if (ar) "ar" else "en"}&query=${Uri.encode(title)}")
            .optJSONArray("results") ?: return null
        var id = 0
        for (i in 0 until minOf(res.length(), 8)) {
            val r = res.getJSONObject(i)
            val names = listOf(r.optString("title"), r.optString("name"), r.optString("original_title"), r.optString("original_name"))
            if (names.none { it.isNotBlank() && LogoMatch.norm(it) == want }) continue
            val date = r.optString("release_date").ifBlank { r.optString("first_air_date") }
            val ry = date.take(4).toIntOrNull()
            if (year != null && ry != null && kotlin.math.abs(ry - year) > 1) continue
            id = r.optInt("id"); break
        }
        if (id == 0) return null
        val logos = getJson("https://api.themoviedb.org/3/$type/$id/images?api_key=${Uri.encode(key)}&include_image_language=ar,en,null")
            .optJSONArray("logos") ?: return null
        val order = if (ar) listOf("ar", "", "en") else listOf("en", "", "ar")
        var best: JSONObject? = null; var bestScore = Double.NEGATIVE_INFINITY
        for (i in 0 until logos.length()) {
            val l = logos.getJSONObject(i)
            val path = l.optString("file_path")
            if (path.isBlank() || path.endsWith(".svg", true)) continue
            val lang = if (l.isNull("iso_639_1")) "" else l.optString("iso_639_1")
            val rank = order.indexOf(lang).takeIf { it >= 0 } ?: continue
            val score = -rank * 100.0 + l.optDouble("vote_average", 0.0) * 5 + minOf(l.optInt("width"), 1000) / 1000.0
            if (score > bestScore) { bestScore = score; best = l }
        }
        val path = best?.optString("file_path") ?: return null
        val bytes = download("https://image.tmdb.org/t/p/w500$path") ?: return null
        if (!decodes(bytes)) return null
        val f = File(d, sha(k) + ".png"); f.writeBytes(bytes)
        synchronized(index) { index.put(k, JSONObject().put("s", "ok").put("f", f.name).put("src", "tmdb").put("id", "$type/$id").put("t", System.currentTimeMillis())) }
        save(d); mem[k] = f
        return f
    }

    private fun none(d: File, k: String): File? {
        synchronized(index) { index.put(k, JSONObject().put("s", "none").put("t", System.currentTimeMillis())) }
        save(d); return null
    }

    private fun manual(d: File, title: String): File? {
        val m = File(d, "manual"); val n = LogoMatch.norm(title)
        File(m, "$n.png").takeIf { it.isFile && decodes(it.readBytes()) }?.let { return it }
        val cfg = File(m, "manual.json").takeIf { it.isFile } ?: return null
        return try {
            val j = JSONObject(cfg.readText())
            val url = j.keys().asSequence().firstOrNull { LogoMatch.norm(it) == n }?.let { j.optString(it) } ?: return null
            val out = File(m, "url_${sha(n)}.png")
            if (out.isFile) return out
            val bytes = download(url) ?: return null
            if (!decodes(bytes)) return null
            out.writeBytes(bytes); out
        } catch (_: Exception) { null }
    }

    /** Adds a manual logo (PNG/JPG/WebP bytes) for a title; used by future UI or tooling. */
    fun addManual(title: String, bytes: ByteArray): Boolean {
        val d = dir ?: return false
        if (!decodes(bytes)) return false
        File(File(d, "manual"), "${LogoMatch.norm(title)}.png").writeBytes(bytes)
        mem.clear(); return true
    }

    /** Forget everything cached (Settings > Storage can call this). Manual logos are kept. */
    fun clearCache() {
        val d = dir ?: return
        d.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        synchronized(index) { index.keys().asSequence().toList().forEach { index.remove(it) } }
        mem.clear()
    }

    private fun decodes(b: ByteArray): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        return o.outWidth > 0 && o.outHeight > 0
    }

    /** True only for a real transparent logo (see [LogoMatch.looksLikeLogo]). */
    private fun verifyBitmap(b: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, bounds)
        if (bounds.outWidth <= 0) return false
        val o = BitmapFactory.Options().apply {
            inSampleSize = maxOf(1, bounds.outWidth / 160); inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(b, 0, b.size, o) ?: return false
        return try {
            val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            bmp.hasAlpha() && LogoMatch.looksLikeLogo(bmp.width, bmp.height, px)
        } finally { bmp.recycle() }
    }

    private fun getJson(url: String): JSONObject {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return JSONObject(r.body!!.string())
        }
    }

    private fun download(url: String): ByteArray? =
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()?.takeIf { it.size in 100..3_000_000 }
        }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

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

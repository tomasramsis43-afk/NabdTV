package com.nabd.tv

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** User-Agent that the provider accepted (shared with the video player). */
@Volatile var httpUa = "IPTVSmartersPro"

class Repo(private val ctx: Context, val store: Store) {
    private val prefs = ctx.getSharedPreferences("nabd", Context.MODE_PRIVATE)
    private val cacheFile get() = File(ctx.filesDir, "playlist2.m3u")
    val dao: ItemDao by lazy { AppDb.get(ctx).items() }

    private suspend fun store(list: List<Channel>, kind: String) {
        noteGroups(kind, list.map { it.group }.distinct())
        dao.replace(kind, list.map { it.toEntity(kind) })
    }

    /* Category order is the provider's own order and stays fixed across refreshes (new categories go to the end). */
    private val orderCache = HashMap<String, List<String>>()
    private fun orderFile(kind: String) = File(ctx.filesDir, "gorder_$kind.txt")
    @Synchronized fun groupOrder(kind: String): List<String> = orderCache.getOrPut(kind) {
        runCatching { orderFile(kind).takeIf { it.exists() }?.readLines()?.filter { it.isNotEmpty() } }.getOrNull() ?: emptyList()
    }
    @Synchronized private fun saveOrder(kind: String, names: List<String>) {
        orderCache[kind] = names
        runCatching { orderFile(kind).writeText(names.joinToString("\n")) }
    }
    /** Provider categories in the provider's order (authoritative). */
    private fun setGroupOrder(kind: String, names: Collection<String>) = saveOrder(kind, names.filter { it.isNotBlank() }.distinct())
    /** Keeps the known order and appends categories seen for the first time (M3U lists and uncategorised items). */
    private fun noteGroups(kind: String, seen: List<String>) {
        val known = groupOrder(kind)
        val extra = seen.filter { it !in known }
        if (extra.isNotEmpty()) saveOrder(kind, known + extra)
    }
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .build()

    init {
        prefs.getString("ua", null)?.let { httpUa = it }
        // One-time upgrade: re-save legacy plain-text login details in encrypted form.
        for (k in listOf("url", "epg", "xt")) {
            val v = prefs.getString(k, "") ?: ""
            if (v.isNotEmpty() && !Secret.isEncrypted(v)) { val e = Secret.enc(v); if (e != v) prefs.edit().putString(k, e).apply() }
        }
    }

    var playlistUrl: String
        get() = Secret.dec(prefs.getString("url", "") ?: "")
        set(v) = prefs.edit().putString("url", Secret.enc(v)).apply()

    var epgUrl: String
        get() = Secret.dec(prefs.getString("epg", "") ?: "")
        set(v) = prefs.edit().putString("epg", Secret.enc(v)).apply()

    var epgManual: Boolean
        get() = prefs.getBoolean("epgManual", false)
        set(v) = prefs.edit().putBoolean("epgManual", v).apply()

    var xtream: String
        get() = Secret.dec(prefs.getString("xt", "") ?: "")
        set(v) = prefs.edit().putString("xt", Secret.enc(v)).apply()

    var liveExt: String
        get() = prefs.getString("liveExt", "ts") ?: "ts"
        set(v) = prefs.edit().putString("liveExt", v).apply()

    val hasXtream: Boolean get() = xt() != null

    private fun xt(): Triple<String, String, String>? {
        val p = xtream.split("\n")
        return if (p.size == 3) Triple(p[0], p[1], p[2]) else null
    }

    private val agents = listOf(
        "IPTVSmartersPro",
        "VLC/3.0.20 LibVLC/3.0.20",
        "okhttp/4.12.0",
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    )

    /** GET with User-Agent rotation: many providers answer 403 to unknown apps. */
    private fun fetch(url: String): String {
        var last = 0
        for (agent in agents) {
            val req = Request.Builder().url(url).header("User-Agent", agent).header("Accept", "*/*").build()
            http.newCall(req).execute().use { r ->
                if (r.isSuccessful) { httpUa = agent; prefs.edit().putString("ua", agent).apply(); return r.body!!.string() }
                last = r.code
            }
            if (last != 401 && last != 403 && last != 429 && last != 456) break
        }
        error("HTTP $last")
    }

    private fun str(o: JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k)

    private fun a(x: String) = x.replace('"', '\'').replace('\n', ' ')

    private fun api(action: String, extra: String = ""): String {
        val (b, u, p) = xt()!!
        return fetch("$b/player_api.php?username=${Uri.encode(u)}&password=${Uri.encode(p)}&action=$action$extra")
    }

    private fun categories(action: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        try {
            val c = JSONArray(api(action))
            for (i in 0 until c.length()) c.getJSONObject(i).let { m[str(it, "category_id")] = str(it, "category_name") }
        } catch (_: Exception) { }
        return m
    }

    /* ───────────── Live ───────────── */

    /** Xtream player_api.php (lighter and less blocked than get.php) → M3U text, including archive info. */
    private fun loadXtream(): String {
        val (base, user, pass) = xt()!!
        val names = categories("get_live_categories")
        if (names.isNotEmpty()) setGroupOrder("live", names.values)
        val streams = JSONArray(api("get_live_streams"))
        val sb = StringBuilder("#EXTM3U\n")
        for (i in 0 until streams.length()) {
            val s = streams.getJSONObject(i)
            val id = str(s, "stream_id")
            if (id.isBlank()) continue
            val days = if (str(s, "tv_archive") == "1") str(s, "tv_archive_duration").toIntOrNull() ?: 0 else 0
            sb.append("#EXTINF:-1 tvg-id=\"").append(a(str(s, "epg_channel_id")))
                .append("\" tvg-logo=\"").append(a(str(s, "stream_icon")))
                .append("\" tvg-chno=\"").append(str(s, "num").toIntOrNull() ?: 0)
                .append("\" catchup-days=\"").append(days)
                .append("\" tn-added=\"").append(str(s, "added").toLongOrNull() ?: 0L)
                .append("\" group-title=\"").append(a(names[str(s, "category_id")] ?: "Other"))
                .append("\",").append(a(str(s, "name"))).append('\n')
            sb.append("$base/live/$user/$pass/$id.$liveExt\n")
        }
        return sb.toString()
    }

    /** Uses the local cache for instant startup; force = re-download. */
    suspend fun load(force: Boolean): List<Channel> = withContext(Dispatchers.IO) {
        if (!force) {
            val rows = dao.channels("live")
            if (rows.isNotEmpty()) return@withContext rows.map { it.toChannel() }
            if (cacheFile.exists()) {   // first run after upgrading from the file cache
                val l = M3uParser.parse(cacheFile.readText())
                if (l.isNotEmpty()) { store(l, "live"); return@withContext l }
            }
        }
        var body: String? = null
        if (xtream.isNotBlank()) {
            try { body = loadXtream() } catch (_: Exception) { }
        }
        if (body == null) {
            val url = playlistUrl
            require(url.isNotBlank()) { "No playlist URL" }
            try {
                body = fetch(url)
            } catch (e: Exception) {
                val m = e.message ?: ""
                if (m.startsWith("HTTP 403") || m.startsWith("HTTP 401"))
                    error("$m: السيرفر رفض الطلب. تأكد من اليوزر والباسورد وأن الاشتراك شغال، أو أن المزوّد يحظر هذا التطبيق أو الـ IP. جرّب نفس البيانات في IPTV Smarters للتأكد.")
                throw e
            }
        }
        val text: String = body!!
        val list = M3uParser.parse(text)
        if (list.isEmpty()) error("رد السيرفر ليس قائمة قنوات. تأكد من البيانات أو الاشتراك. الرد: " + text.trim().take(150))
        cacheFile.writeText(text)
        store(list, "live")
        if (!epgManual) M3uParser.epgFromHeader(text)?.let { epgUrl = it }
        list
    }

    /* ───────────── EPG ───────────── */

    /**
     * Downloads the XMLTV guide (refresh every 6h), auto-matches it to the channels and returns
     * programmes keyed by channel url. The window covers past days (for catch-up) and the next 36 hours.
     */
    suspend fun loadEpg(channels: List<Channel>, force: Boolean): Map<String, List<Prog>> =
        withContext(Dispatchers.IO) {
            try {
                val f = File(ctx.filesDir, "epg.xml")
                val stale = !f.exists() || System.currentTimeMillis() - f.lastModified() > 6 * 3600_000L
                if ((force || stale) && epgUrl.isNotBlank()) {
                    try {
                        val req = Request.Builder().url(epgUrl).header("User-Agent", httpUa).build()
                        http.newCall(req).execute().use { r ->
                            if (r.isSuccessful) r.body!!.byteStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                        }
                    } catch (_: Exception) { }
                }
                if (!f.exists()) return@withContext emptyMap()

                fun open(): java.io.InputStream {
                    val raw = f.inputStream().buffered()
                    raw.mark(2); val b1 = raw.read(); val b2 = raw.read(); raw.reset()
                    return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw) else raw
                }

                val index = open().use { EpgParser.readChannels(it) }
                val matched = EpgMatcher.match(channels, index, store.epgOverrides())
                if (matched.isEmpty()) return@withContext emptyMap()
                val now = System.currentTimeMillis()
                val byXml = open().use { EpgParser.parse(it, matched.values.toSet(), now - 3 * 86_400_000L, now + 36 * 3600_000L) }
                val out = HashMap<String, List<Prog>>(matched.size)
                for ((url, xml) in matched) byXml[xml]?.let { out[url] = it }
                out
            } catch (e: Exception) { emptyMap() }
        }

    /** Channel ids found in the cached guide (for the manual EPG match screen). */
    suspend fun guideChannelNames(): List<String> = withContext(Dispatchers.IO) {
        try {
            val f = File(ctx.filesDir, "epg.xml")
            if (!f.exists()) return@withContext emptyList()
            val raw = f.inputStream().buffered()
            raw.mark(2); val b1 = raw.read(); val b2 = raw.read(); raw.reset()
            val src = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw) else raw
            src.use { EpgParser.readChannels(it).ids.values.sorted() }
        } catch (e: Exception) { emptyList() }
    }

    /* ───────────── Catch-up ───────────── */

    private fun streamIdOf(url: String): String = url.substringAfterLast('/').substringBeforeLast('.')

    /** Replay URL for a past programme, or null if the channel has no archive. */
    fun catchupUrl(c: Channel, p: Prog): String? {
        if (!c.hasCatchup) return null
        val offset = store.catchupOffsetHours * 3600_000L
        val src = c.catchupSource
        if (!src.isNullOrBlank()) {
            fun f(pattern: String, t: Long) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t + offset))
            var u = src
                .replace("{utc}", (p.start / 1000).toString()).replace("{utcend}", (p.stop / 1000).toString())
                .replace("{start}", (p.start / 1000).toString()).replace("{end}", (p.stop / 1000).toString())
                .replace("{duration}", ((p.stop - p.start) / 1000).toString())
                .replace("{Y}", f("yyyy", p.start)).replace("{m}", f("MM", p.start)).replace("{d}", f("dd", p.start))
                .replace("{H}", f("HH", p.start)).replace("{M}", f("mm", p.start)).replace("{S}", f("ss", p.start))
            if (!u.startsWith("http")) u = c.url + u
            return u
        }
        val (b, u, pass) = xt() ?: return null
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val minutes = ((p.stop - p.start) / 60_000L).coerceAtLeast(1)
        return "$b/timeshift/$u/$pass/$minutes/${fmt.format(Date(p.start + offset))}/${streamIdOf(c.url)}.ts"
    }

    /* ───────────── VOD ───────────── */

    /** Rating on a 10-point scale; falls back to the 5-point field (x2) when the provider only sends that. */
    private fun ratingOf(o: JSONObject): String {
        val r = str(o, "rating")
        if (r.isNotBlank()) return r
        val f = str(o, "rating_5based").toDoubleOrNull() ?: return ""
        return if (f > 0) "%.1f".format(java.util.Locale.US, f * 2) else ""
    }

    private fun buildM3u(arr: JSONArray, names: Map<String, String>, icon: String, url: (JSONObject) -> String?): String {
        val sb = StringBuilder("#EXTM3U\n")
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val u = url(s) ?: continue
            val rating = ratingOf(s)
            sb.append("#EXTINF:-1 tvg-logo=\"").append(a(str(s, icon)))
                .append("\" tn-rating=\"").append(a(rating))
                .append("\" tn-added=\"").append((str(s, "added").toLongOrNull() ?: str(s, "last_modified").toLongOrNull()) ?: 0L)
                .append("\" group-title=\"").append(a(names[str(s, "category_id")] ?: "Other"))
                .append("\",").append(a(str(s, "name"))).append('\n').append(u).append('\n')
        }
        return sb.toString()
    }

    suspend fun clearVod() = withContext(Dispatchers.IO) {
        dao.clear("movie"); dao.clear("series")
        File(ctx.filesDir, "movies.m3u").delete(); File(ctx.filesDir, "series.m3u").delete()
    }

    suspend fun loadMovies(force: Boolean): List<Channel> = withContext(Dispatchers.IO) {
        val (b, u, p) = xt() ?: return@withContext emptyList()
        if (!force) dao.channels("movie").takeIf { it.isNotEmpty() }?.let { r -> return@withContext r.map { it.toChannel() } }
        val names = categories("get_vod_categories")
        if (names.isNotEmpty()) setGroupOrder("movie", names.values)
        val t = buildM3u(JSONArray(api("get_vod_streams")), names, "stream_icon") { s ->
            val id = str(s, "stream_id")
            if (id.isBlank()) null else "$b/movie/$u/$p/$id.${str(s, "container_extension").ifBlank { "mp4" }}"
        }
        val list = M3uParser.parse(t)
        store(list, "movie")
        list
    }

    suspend fun loadSeries(force: Boolean): List<Channel> = withContext(Dispatchers.IO) {
        if (xt() == null) return@withContext emptyList()
        if (!force) dao.channels("series").takeIf { it.isNotEmpty() }?.let { r -> return@withContext r.map { it.toChannel() } }
        val names = categories("get_series_categories")
        if (names.isNotEmpty()) setGroupOrder("series", names.values)
        val t = buildM3u(JSONArray(api("get_series")), names, "cover") { s ->
            val id = str(s, "series_id")
            if (id.isBlank()) null else "series:$id"
        }
        val list = M3uParser.parse(t)
        store(list, "series")
        list
    }

    suspend fun loadEpisodes(seriesId: Int): List<Episode> = withContext(Dispatchers.IO) {
        val (b, u, p) = xt() ?: return@withContext emptyList()
        val eps = JSONObject(api("get_series_info", "&series_id=$seriesId")).optJSONObject("episodes")
            ?: return@withContext emptyList()
        val out = ArrayList<Episode>()
        for (k in eps.keys()) {
            val arr = eps.optJSONArray(k) ?: continue
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val id = str(e, "id")
                if (id.isBlank()) continue
                val ext = str(e, "container_extension").ifBlank { "mp4" }
                val info = e.optJSONObject("info")
                out += Episode(
                    k.toIntOrNull() ?: 0, str(e, "episode_num").toIntOrNull() ?: (i + 1),
                    str(e, "title"), "$b/series/$u/$p/$id.$ext",
                    info?.let { str(it, "plot") } ?: "",
                    info?.let { str(it, "movie_image").ifBlank { null } }
                )
            }
        }
        out.sortedWith(compareBy({ it.season }, { it.num }))
    }

    /* ───────────── Metadata (Xtream first, TMDB fallback) ───────────── */

    private fun firstOf(o: JSONObject, k: String): String? {
        val arr = o.optJSONArray(k)
        if (arr != null && arr.length() > 0) return arr.optString(0).ifBlank { null }
        return str(o, k).ifBlank { null }
    }

    private fun parseInfo(i: JSONObject) = VodInfo(
        plot = str(i, "plot").ifBlank { str(i, "description") },
        cast = str(i, "cast").ifBlank { str(i, "actors") },
        director = str(i, "director"),
        genre = str(i, "genre"),
        rating = str(i, "rating").ifBlank { str(i, "rating_5based") },
        year = (str(i, "releasedate").ifBlank { str(i, "releaseDate") }).take(4),
        backdrop = firstOf(i, "backdrop_path"),
        duration = str(i, "duration")
    )

    suspend fun vodInfo(c: Channel, kind: Kind): VodInfo? = withContext(Dispatchers.IO) {
        var info: VodInfo? = null
        if (xt() != null) {
            try {
                info = if (kind == Kind.Series) {
                    val id = c.url.removePrefix("series:").toIntOrNull()
                    if (id == null) null else JSONObject(api("get_series_info", "&series_id=$id")).optJSONObject("info")?.let { parseInfo(it) }
                } else {
                    JSONObject(api("get_vod_info", "&vod_id=${streamIdOf(c.url)}")).optJSONObject("info")?.let { parseInfo(it) }
                }
            } catch (_: Exception) { }
        }
        if (info == null || info.plot.isBlank()) {
            val t = tmdb(c.name, kind == Kind.Series)
            if (t != null) info = if (info == null) t else info.copy(
                plot = t.plot, rating = info.rating.ifBlank { t.rating }, year = info.year.ifBlank { t.year },
                backdrop = info.backdrop ?: t.backdrop
            )
        }
        info
    }

    private fun tmdb(title: String, series: Boolean): VodInfo? {
        val key = store.tmdbKey
        if (key.isBlank()) return null
        return try {
            val clean = title.replace(Regex("""[\[(].*?[\])]"""), " ").replace(Regex("""\s+"""), " ").trim()
            val kind = if (series) "tv" else "movie"
            val url = "https://api.themoviedb.org/3/search/$kind?api_key=${Uri.encode(key)}&language=ar&query=${Uri.encode(clean)}"
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val res = JSONObject(r.body!!.string()).optJSONArray("results") ?: return null
                if (res.length() == 0) return null
                val o = res.getJSONObject(0)
                val back = str(o, "backdrop_path")
                VodInfo(
                    plot = str(o, "overview"),
                    rating = str(o, "vote_average").take(3),
                    year = (str(o, "release_date").ifBlank { str(o, "first_air_date") }).take(4),
                    backdrop = if (back.isBlank()) null else "https://image.tmdb.org/t/p/w780$back"
                )
            }
        } catch (_: Exception) { null }
    }

    /* ───────────── Health probe ───────────── */

    private fun looksLike(buf: ByteArray, n: Int): String {
        if (n <= 0) return "فارغ"
        val s = String(buf, 0, n, Charsets.ISO_8859_1)
        return when {
            s.startsWith("#EXTM3U") -> "HLS"
            buf[0] == 0x47.toByte() -> "MPEG-TS"
            s.startsWith("<") -> "صفحة HTML/خطأ"
            s.startsWith("{") -> "JSON/خطأ"
            else -> "بيانات"
        }
    }

    /** One short, ranged request: classifies a stream as ok / slow / dead and measures time-to-first-byte. */
    fun probeStream(url: String): HealthRecord {
        val c = http.newBuilder().readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
        val t0 = System.currentTimeMillis()
        val old = store.health(url)
        return try {
            val req = Request.Builder().url(url).header("User-Agent", httpUa).header("Range", "bytes=0-1023").build()
            c.newCall(req).execute().use { r ->
                val buf = ByteArray(64)
                val n = if (r.isSuccessful) r.body!!.byteStream().read(buf) else 0
                val ms = System.currentTimeMillis() - t0
                val kind = looksLike(buf, n)
                val good = r.isSuccessful && (kind == "HLS" || kind == "MPEG-TS" || kind == "بيانات")
                val state = when {
                    !good -> Health.Dead
                    ms > 4000 -> Health.Slow
                    else -> Health.Ok
                }
                HealthRecord(state, ms, System.currentTimeMillis(), if (good) 0 else (old?.failures ?: 0) + 1)
            }
        } catch (e: Exception) {
            HealthRecord(Health.Dead, System.currentTimeMillis() - t0, System.currentTimeMillis(), (old?.failures ?: 0) + 1)
        }
    }

    /** Self-test: account status and connection limit (the stream-level probes live in HealthChecker). */
    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        val (b, u, p) = xt() ?: return@withContext "الدخول ليس بـ Xtream"
        val sb = StringBuilder()
        try {
            val ui = JSONObject(fetch("$b/player_api.php?username=${Uri.encode(u)}&password=${Uri.encode(p)}")).optJSONObject("user_info")
            if (ui == null) sb.append("لا توجد بيانات حساب في رد السيرفر\n") else {
                val exp = str(ui, "exp_date").toLongOrNull()
                    ?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it * 1000)) } ?: "غير محدود"
                sb.append("الحساب: ${str(ui, "status")} (auth=${str(ui, "auth")}) · ينتهي: $exp\n")
                sb.append("الاتصالات الآن: ${str(ui, "active_cons")} من ${str(ui, "max_connections")}\n")
            }
        } catch (e: Exception) { sb.append("فحص الحساب فشل: ${e.message}\n") }
        val live = cacheFile.takeIf { it.exists() }?.let { M3uParser.parse(it.readText()).firstOrNull()?.url }
        if (live == null) sb.append("لا توجد قنوات محمّلة للفحص\n") else {
            val stem = live.removeSuffix(".m3u8").removeSuffix(".ts")
            val ts = probeStream("$stem.ts")
            val hls = probeStream("$stem.m3u8")
            sb.append("مباشر ts: ${ts.state} (${ts.latencyMs}ms) · m3u8: ${hls.state} (${hls.latencyMs}ms)\n")
            if (hls.state != Health.Dead && ts.state == Health.Dead) {
                liveExt = "m3u8"; cacheFile.delete()
                sb.append("→ تم اعتماد m3u8. اضغط تحديث في قسم المباشر.\n")
            } else if (ts.state != Health.Dead && hls.state == Health.Dead) liveExt = "ts"
            if (ts.state == Health.Dead && hls.state == Health.Dead)
                sb.append("→ القنوات المباشرة لا تفتح: راجع الاتصالات المتزامنة والاشتراك، أو أن المزوّد يحظر الجهاز/الـ IP.\n")
        }
        sb.toString()
    }
}

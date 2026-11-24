package com.nabd.tv

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
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

    /**
     * The local cache holds one set of lists PER provider: rows are kept under "live@<source id>" etc., so switching between saved
     * providers shows the other one's lists at once instead of downloading them again.
     */
    @Volatile private var slotKey: String? = null
    fun slotId(): String = slotKey ?: (currentSourceId() ?: "none").also { slotKey = it }
    private fun slot(kind: String) = "$kind@${slotId()}"

    private val slotLock = kotlinx.coroutines.sync.Mutex()
    /** One-time move of the pre-upgrade rows (plain "live" / "movie" / "series") to the slot of the provider that was in use. */
    private suspend fun migrateSlots() = slotLock.withLock {
        if (prefs.getBoolean("slots1", false)) return@withLock
        for (k in listOf("live", "movie", "series")) {
            val rows = dao.channels(k)
            if (rows.isNotEmpty()) { dao.replace(slot(k), rows.map { it.toChannel().toEntity(slot(k)) }); dao.clear(k) }
        }
        prefs.edit().putBoolean("slots1", true).apply()
    }

    private suspend fun store(list: List<Channel>, kind: String) {
        noteGroups(kind, list.map { it.group }.distinct())
        dao.replace(slot(kind), list.map { it.toEntity(slot(kind)) })
    }

    /** A different provider is about to be used with its own cached lists: the guide file on disk belongs to the previous one. */
    fun dropEpgCache() { File(ctx.filesDir, "epg.xml").delete(); File(ctx.filesDir, "epg_cache.bin").delete() }

    /* Category order is the provider's own order and stays fixed across refreshes (new categories go to the end). */
    private val orderCache = HashMap<String, List<String>>()
    private fun orderFile(kind: String) = File(ctx.filesDir, "gorder_${kind}_${slotId()}.txt")
    @Synchronized fun groupOrder(kind: String): List<String> = orderCache.getOrPut("$kind|${slotId()}") {
        runCatching { orderFile(kind).takeIf { it.exists() }?.readLines()?.filter { it.isNotEmpty() } }.getOrNull() ?: emptyList()
    }
    @Synchronized private fun saveOrder(kind: String, names: List<String>) {
        orderCache["$kind|${slotId()}"] = names
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
    val http: OkHttpClient = Net.client(15, 90)

    init {
        if (!prefs.getBoolean("slots1o", false)) {
            runCatching {
                for (k in listOf("live", "movie", "series")) {
                    val old = File(ctx.filesDir, "gorder_$k.txt")
                    if (old.exists()) old.copyTo(File(ctx.filesDir, "gorder_${k}_${slotId()}.txt"), overwrite = false)
                }
            }
            prefs.edit().putBoolean("slots1o", true).apply()
        }
        prefs.getString("ua", null)?.let { httpUa = it }
        // One-time upgrade: re-save legacy plain-text login details in encrypted form.
        for (k in listOf("url", "epg", "xt")) {
            val v = prefs.getString(k, "") ?: ""
            if (v.isNotEmpty() && !Secret.isEncrypted(v)) { val e = Secret.enc(v); if (e != v) prefs.edit().putString(k, e).apply() }
        }
    }

    var playlistUrl: String
        get() = Secret.dec(prefs.getString("url", "") ?: "")
        set(v) { slotKey = null; prefs.edit().putString("url", Secret.enc(v)).apply() }

    var epgUrl: String
        get() = Secret.dec(prefs.getString("epg", "") ?: "")
        set(v) = prefs.edit().putString("epg", Secret.enc(v)).apply()

    var epgManual: Boolean
        get() = prefs.getBoolean("epgManual", false)
        set(v) = prefs.edit().putBoolean("epgManual", v).apply()

    var xtream: String
        get() = Secret.dec(prefs.getString("xt", "") ?: "")
        set(v) { slotKey = null; prefs.edit().putString("xt", Secret.enc(v)).apply() }

    var liveExt: String
        get() = prefs.getString("liveExt", "ts") ?: "ts"
        set(v) = prefs.edit().putString("liveExt", v).apply()

    val hasXtream: Boolean get() = xt() != null

    /* ───────── saved sources (switch between providers without typing the login again) ───────── */
    private fun sourceId(kind: String, a: String, user: String) = Integer.toHexString((kind + "|" + a + "|" + user).hashCode())

    fun savedSources(): List<SavedSource> {
        val raw = Secret.dec(prefs.getString("sources", "") ?: "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val a = org.json.JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                SavedSource(o.getString("id"), o.getString("name"), o.getString("kind"), o.getString("a"), o.optString("user"), o.optString("pass"))
            }
        }.getOrDefault(emptyList())
    }

    private fun writeSources(list: List<SavedSource>) {
        val a = org.json.JSONArray()
        list.forEach { a.put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind).put("a", it.address).put("user", it.user).put("pass", it.pass)) }
        prefs.edit().putString("sources", Secret.enc(a.toString())).apply()
    }

    /** Adds (or refreshes) a source in the saved list. [address] is the Xtream host or the playlist URL. */
    fun rememberSource(kind: String, address: String, user: String = "", pass: String = "") {
        if (address.isBlank()) return
        val host = runCatching { java.net.URI(address).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: address.take(40)
        val src = SavedSource(sourceId(kind, address, user), if (kind == "xtream" && user.isNotBlank()) "$host · $user" else host, kind, address, user, pass)
        writeSources(listOf(src) + savedSources().filter { it.id != src.id })
    }

    fun forgetSource(id: String) = writeSources(savedSources().filter { it.id != id })

    /** The login currently in use as a saved source (older installs never saved theirs). */
    fun currentSourceId(): String? {
        xt()?.let { (h, u, _) -> return sourceId("xtream", h, u) }
        return playlistUrl.takeIf { it.isNotBlank() }?.let { sourceId("m3u", it, "") }
    }

    fun ensureCurrentSaved() {
        val id = currentSourceId() ?: return
        if (savedSources().any { it.id == id }) return
        xt()?.let { (h, u, p) -> rememberSource("xtream", h, u, p) } ?: rememberSource("m3u", playlistUrl)
    }

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
        } catch (e: Exception) { Diag.note("Repo#1", e) }
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
        migrateSlots()
        if (!force) {
            val rows = dao.channels(slot("live"))
            if (rows.isNotEmpty()) return@withContext rows.map { it.toChannel() }
            // The single-file cache belongs to whichever provider was used before the per-provider cache: never offer it to another one.
            if (cacheFile.exists() && savedSources().size <= 1) {   // first run after upgrading from the file cache
                val l = M3uParser.parse(cacheFile.readText())
                if (l.isNotEmpty()) { store(l, "live"); return@withContext l }
            }
        }
        var body: String? = null
        if (xtream.isNotBlank()) {
            try { body = loadXtream() } catch (e: Exception) { Diag.note("xtream load", e) }
        }
        if (body == null) {
            val url = playlistUrl
            require(url.isNotBlank()) { "No playlist URL" }
            try {
                body = fetch(url)
            } catch (e: Exception) {
                val m = e.message ?: ""
                if (m.startsWith("HTTP 403") || m.startsWith("HTTP 401"))
                    error(S(R.string.x_repo_denied, m))
                throw e
            }
        }
        val text: String = body!!
        val list = M3uParser.parse(text)
        if (list.isEmpty()) error(S(R.string.a_0b8880ec) + text.trim().take(150))
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
    /** Identifies what the parsed EPG depends on: the file, the channel list and the manual matches. */
    private fun epgSignature(f: File, channels: List<Channel>): String {
        var h = 1125899906842597L
        for (c in channels) { h = 31 * h + c.url.hashCode(); h = 31 * h + (c.tvgId?.hashCode() ?: 0); h = 31 * h + c.name.hashCode() }
        return "${f.lastModified()}:${f.length()}:$h:${store.epgOverrides().hashCode()}"
    }

    private fun writeEpgStr(o: java.io.DataOutputStream, s: String) { val b = s.toByteArray(Charsets.UTF_8); o.writeInt(b.size); o.write(b) }
    private fun readEpgStr(i: java.io.DataInputStream): String { val b = ByteArray(i.readInt()); i.readFully(b); return String(b, Charsets.UTF_8) }

    private fun writeEpgCache(f: File, sig: String, out: Map<String, List<Prog>>) {
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream())).use { o ->
                o.writeInt(0x4E504401); o.writeUTF(sig); o.writeLong(System.currentTimeMillis()); o.writeInt(out.size)
                for ((u, l) in out) {
                    writeEpgStr(o, u); o.writeInt(l.size)
                    for (p in l) { o.writeLong(p.start); o.writeLong(p.stop); writeEpgStr(o, p.title); writeEpgStr(o, p.desc) }
                }
            }
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (_: Exception) { f.delete() }
    }

    private fun readEpgCache(f: File, sig: String): Map<String, List<Prog>>? {
        if (!f.exists()) return null
        return try {
            java.io.DataInputStream(java.io.BufferedInputStream(f.inputStream())).use { i ->
                if (i.readInt() != 0x4E504401 || i.readUTF() != sig) return null
                if (System.currentTimeMillis() - i.readLong() > 6 * 3600_000L) return null
                val n = i.readInt()
                val m = HashMap<String, List<Prog>>(n)
                repeat(n) {
                    val u = readEpgStr(i)
                    val c = i.readInt()
                    val l = ArrayList<Prog>(c)
                    repeat(c) { l += Prog(i.readLong(), i.readLong(), readEpgStr(i), readEpgStr(i)) }
                    m[u] = l
                }
                m
            }
        } catch (_: Exception) { null }
    }

    suspend fun loadEpg(channels: List<Channel>, force: Boolean): Map<String, List<Prog>> =
        withContext(Dispatchers.IO) {
            if (!store.epgEnabled) return@withContext emptyMap()
            try {
                val f = File(ctx.filesDir, "epg.xml")
                val stale = !f.exists() || System.currentTimeMillis() - f.lastModified() > 6 * 3600_000L
                if ((force || stale) && epgUrl.isNotBlank()) {
                    try {
                        val req = Request.Builder().url(epgUrl).header("User-Agent", httpUa).build()
                        http.newCall(req).execute().use { r ->
                            if (r.isSuccessful) r.body!!.byteStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                        }
                    } catch (e: Exception) { Diag.note("Repo#3", e) }
                }
                if (!f.exists()) return@withContext emptyMap()

                fun open(): java.io.InputStream {
                    val raw = f.inputStream().buffered()
                    raw.mark(2); val b1 = raw.read(); val b2 = raw.read(); raw.reset()
                    return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw) else raw
                }

                // The XMLTV file is parsed twice (channel index, then programs) - heavy for a big provider file. The result
                // is cached on disk and reused while the file, the channel list and the manual matches are unchanged
                // (and the cache is under 6 h old, so the "now / next" window stays current).
                val cacheFile = File(ctx.filesDir, "epg_cache.bin")
                val sig = epgSignature(f, channels)
                readEpgCache(cacheFile, sig)?.let { return@withContext it }

                val index = open().use { EpgParser.readChannels(it) }
                val matched = EpgMatcher.match(channels, index, store.epgOverrides())
                val out = HashMap<String, List<Prog>>(matched.size)
                if (matched.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val byXml = open().use { EpgParser.parse(it, matched.values.toSet(), now - 3 * 86_400_000L, now + 36 * 3600_000L) }
                    for ((url, xml) in matched) byXml[xml]?.let { out[url] = it }
                }
                writeEpgCache(cacheFile, sig, out)
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

    private fun buildM3u(arr: JSONArray, names: Map<String, String>, icon: Any, url: (JSONObject) -> String?): String {
        val iconFields = if (icon is List<*>) icon.filterIsInstance<String>() else listOf(icon.toString())
        val sb = StringBuilder("#EXTM3U\n")
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val u = url(s) ?: continue
            val rating = ratingOf(s)
            val logo = iconFields.firstOrNull { str(s, it).isNotBlank() }?.let { str(s, it) } ?: ""
            sb.append("#EXTINF:-1 tvg-logo=\"").append(a(logo))
                .append("\" tn-rating=\"").append(a(rating))
                .append("\" tn-added=\"").append((str(s, "added").toLongOrNull() ?: str(s, "last_modified").toLongOrNull()) ?: 0L)
                .append("\" group-title=\"").append(a(names[str(s, "category_id")] ?: "Other"))
                .append("\",").append(a(str(s, "name"))).append('\n').append(u).append('\n')
        }
        return sb.toString()
    }

    suspend fun clearVod() = withContext(Dispatchers.IO) {
        migrateSlots(); dao.clear(slot("movie")); dao.clear(slot("series"))
        File(ctx.filesDir, "movies.m3u").delete(); File(ctx.filesDir, "series.m3u").delete()
    }

    suspend fun loadMovies(force: Boolean): List<Channel> = withContext(Dispatchers.IO) {
        val (b, u, p) = xt() ?: return@withContext emptyList()
        migrateSlots()
        if (!force) dao.channels(slot("movie")).takeIf { it.isNotEmpty() }?.let { r -> return@withContext r.map { it.toChannel() } }
        val names = categories("get_vod_categories")
        if (names.isNotEmpty()) setGroupOrder("movie", names.values)
        val t = buildM3u(JSONArray(api("get_vod_streams")), names, listOf("cover", "stream_icon")) { s ->
            val id = str(s, "stream_id")
            if (id.isBlank()) null else "$b/movie/$u/$p/$id.${str(s, "container_extension").ifBlank { "mp4" }}"
        }
        val list = M3uParser.parse(t)
        store(list, "movie")
        list
    }

    suspend fun loadSeries(force: Boolean): List<Channel> = withContext(Dispatchers.IO) {
        if (xt() == null) return@withContext emptyList()
        migrateSlots()
        if (!force) dao.channels(slot("series")).takeIf { it.isNotEmpty() }?.let { r -> return@withContext r.map { it.toChannel() } }
        val names = categories("get_series_categories")
        if (names.isNotEmpty()) setGroupOrder("series", names.values)
        val t = buildM3u(JSONArray(api("get_series")), names, listOf("cover", "stream_icon")) { s ->
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
        duration = str(i, "duration"),
        trailer = youtubeId(str(i, "youtube_trailer").ifBlank { str(i, "trailer") }),
        poster = str(i, "cover_big").ifBlank { str(i, "movie_image") }.ifBlank { str(i, "cover") }.ifBlank { null }
    )

    /** Providers send either a bare YouTube id or a full URL. */
    private fun youtubeId(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        Regex("""(?:v=|youtu\.be/|embed/)([A-Za-z0-9_-]{11})""").find(t)?.let { return it.groupValues[1] }
        return if (Regex("""[A-Za-z0-9_-]{11}""").matches(t)) t else ""
    }

    // Title details are cached on the device (memory + files in the app cache) so reopening a title is instant, even after a restart.
    private val infoCache = object : LinkedHashMap<String, Pair<Long, VodInfo>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, VodInfo>>?) = size > 200
    }
    private val INFO_TTL = 7L * 24 * 3600 * 1000
    private val infoDir by lazy { File(ctx.cacheDir, "vodinfo").also { it.mkdirs() } }
    private fun infoKey(c: Channel, kind: Kind) = "v4|${kind.name}|${c.url}|" + store.tmdbKey.isNotBlank()
    private fun infoFile(k: String) =
        File(infoDir, java.security.MessageDigest.getInstance("SHA-1").digest(k.toByteArray()).joinToString("") { "%02x".format(it) }.take(24) + ".json")

    /** Details already in memory (fresh), or null. Safe to call on the main thread. */
    fun cachedInfo(c: Channel, kind: Kind): VodInfo? = synchronized(infoCache) {
        infoCache[infoKey(c, kind)]?.takeIf { System.currentTimeMillis() - it.first < INFO_TTL }?.second
    }

    suspend fun vodInfo(c: Channel, kind: Kind): VodInfo? {
        cachedInfo(c, kind)?.let { return it }
        val k = infoKey(c, kind)
        val fromDisk = withContext(Dispatchers.IO) { readInfo(k) }
        if (fromDisk != null) {
            synchronized(infoCache) { infoCache[k] = fromDisk.first to fromDisk.second }
            return fromDisk.second
        }
        val r = loadVodInfo(c, kind)
        if (r != null) {
            val now = System.currentTimeMillis()
            synchronized(infoCache) { infoCache[k] = now to r }
            withContext(Dispatchers.IO) { writeInfo(k, now, r) }
        }
        return r
    }

    private fun readInfo(k: String): Pair<Long, VodInfo>? = runCatching {
        val f = infoFile(k)
        if (!f.isFile) return null
        val j = JSONObject(f.readText())
        val t = j.getLong("t")
        if (System.currentTimeMillis() - t > INFO_TTL) { f.delete(); return null }
        val ps = j.optJSONArray("people")
        val people = if (ps == null) emptyList() else (0 until ps.length()).map { i ->
            ps.getJSONObject(i).let { Person(it.optString("n"), it.optString("p").ifBlank { null }, it.optString("r"), it.optInt("i")) }
        }
        t to VodInfo(
            j.optString("plot"), j.optString("cast"), j.optString("director"), j.optString("genre"), j.optString("rating"),
            j.optString("year"), j.optString("backdrop").ifBlank { null }, j.optString("duration"), j.optString("trailer"),
            j.optString("poster").ifBlank { null }, people
        )
    }.getOrNull()

    private fun writeInfo(k: String, t: Long, v: VodInfo) = runCatching {
        val ps = org.json.JSONArray()
        v.people.forEach { ps.put(JSONObject().put("n", it.name).put("p", it.photo ?: "").put("r", it.role).put("i", it.id)) }
        val j = JSONObject().put("t", t).put("plot", v.plot).put("cast", v.cast).put("director", v.director).put("genre", v.genre)
            .put("rating", v.rating).put("year", v.year).put("backdrop", v.backdrop ?: "").put("duration", v.duration)
            .put("trailer", v.trailer).put("poster", v.poster ?: "").put("people", ps)
        infoFile(k).writeText(j.toString())
    }

    private suspend fun loadVodInfo(c: Channel, kind: Kind): VodInfo? = withContext(Dispatchers.IO) {
        var info: VodInfo? = null
        if (xt() != null) {
            try {
                info = if (kind == Kind.Series) {
                    val id = c.url.removePrefix("series:").toIntOrNull()
                    if (id == null) null else JSONObject(api("get_series_info", "&series_id=$id")).optJSONObject("info")?.let { parseInfo(it) }
                } else {
                    JSONObject(api("get_vod_info", "&vod_id=${streamIdOf(c.url)}")).optJSONObject("info")?.let { parseInfo(it) }
                }
            } catch (e: Exception) { Diag.note("Repo#4", e) }
        }
        if (info == null || info.plot.isBlank()) {
            val t = tmdb(c.name, kind == Kind.Series)
            if (t != null) info = if (info == null) t else info.copy(
                plot = t.plot, rating = info.rating.ifBlank { t.rating }, year = info.year.ifBlank { t.year },
                backdrop = info.backdrop ?: t.backdrop
            )
        }
        // Cast photos and a trailer from TMDB (only with a key and an exact match) fill what the provider lacks.
        if (kind != Kind.Live) {
            val ex = Artwork.extras(c.name, kind, info?.year?.toIntOrNull())
            if (ex != null) info = (info ?: VodInfo()).copy(
                trailer = info?.trailer.orEmpty().ifBlank { ex.trailer },
                people = ex.people
            )
        }
        // TMDB's verified match: its 0-10 rating always replaces the provider's number (cleared when TMDB has none, so a provider
        // number never shows as if it were TMDB's); description and year only fill what the provider left blank.
        if (kind != Kind.Live && store.tmdbKey.isNotBlank()) {
            val h = Artwork.hit(c.name, kind, info?.year?.toIntOrNull())
            val va = h?.optDouble("vote_average", 0.0) ?: 0.0
            val date = h?.optString("release_date").orEmpty().ifBlank { h?.optString("first_air_date").orEmpty() }
            val base = info ?: VodInfo()
            info = base.copy(
                plot = base.plot.ifBlank { h?.optString("overview").orEmpty() },
                year = base.year.ifBlank { date.take(4) },
                rating = if (va > 0) String.format(java.util.Locale.US, "%.1f", va) else ""
            )
        }
        // Provider gave no backdrop: look for a verified landscape one (never a poster stretched wide).
        // With a TMDB key the verified clean artwork wins over the provider's (often watermarked) one; the provider's stays as fallback.
        if (kind != Kind.Live) {
            val hasKey = store.tmdbKey.isNotBlank()
            if (hasKey || info?.backdrop.isNullOrBlank()) {
                Artwork.backdrop(c.name, kind, info?.year?.toIntOrNull())?.let { a ->
                    info = (info ?: VodInfo()).copy(backdrop = a.url)
                }
            }
            if (hasKey) {
                Artwork.poster(c.name, kind, info?.year?.toIntOrNull())?.let { a ->
                    info = (info ?: VodInfo()).copy(poster = a.url)
                }
            }
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
                    backdrop = if (back.isBlank()) null else "https://image.tmdb.org/t/p/w1280$back"
                )
            }
        } catch (_: Exception) { null }
    }

    /* ───────────── TMDB episode stills ───────────── */

    private val tmdbIds = HashMap<String, Int>()
    private val tmdbMiss = HashSet<String>()
    private val stillCache = HashMap<String, Map<Int, String>>()

    private fun norm(t: String) = t.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "")

    /**
     * Real episode stills for one season from TMDB, keyed by episode number. The series is accepted only when its
     * TMDB title equals the provider title (exact match after normalising); otherwise nothing is returned,
     * so an image is never attached to the wrong show.
     */
    suspend fun tmdbStills(title: String, season: Int): Map<Int, String> = withContext(Dispatchers.IO) {
        val key = store.tmdbKey
        if (key.isBlank() || season <= 0) return@withContext emptyMap()
        val clean = title.replace(Regex("""[\[(].*?[\])]"""), " ").replace(Regex("""\s+"""), " ").trim()
        val want = norm(clean)
        if (want.isEmpty()) return@withContext emptyMap()
        fun get(url: String): JSONObject? = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) JSONObject(r.body!!.string()) else null
        }
        try {
            if (synchronized(tmdbIds) { want in tmdbMiss }) return@withContext emptyMap()
            val id = synchronized(tmdbIds) { tmdbIds[want] } ?: run {
                val res = get("https://api.themoviedb.org/3/search/tv?api_key=${Uri.encode(key)}&language=ar&query=${Uri.encode(clean)}")
                    ?.optJSONArray("results")
                var found: Int? = null
                if (res != null) for (i in 0 until minOf(res.length(), 5)) {
                    val o = res.getJSONObject(i)
                    if (norm(str(o, "name")) == want || norm(str(o, "original_name")) == want) { found = o.optInt("id"); break }
                }
                // Cache hits and confirmed misses; a failed request (res == null) is retried later.
                synchronized(tmdbIds) { if (found != null) tmdbIds[want] = found else if (res != null) tmdbMiss.add(want) }
                found
            } ?: return@withContext emptyMap()
            val ck = "$id:$season"
            synchronized(stillCache) { stillCache[ck] }?.let { return@withContext it }
            val eps = get("https://api.themoviedb.org/3/tv/$id/season/$season?api_key=${Uri.encode(key)}&language=ar")
                ?.optJSONArray("episodes") ?: return@withContext emptyMap()
            val out = HashMap<Int, String>()
            for (i in 0 until eps.length()) {
                val e = eps.getJSONObject(i)
                val path = str(e, "still_path")
                if (path.isNotBlank()) out[e.optInt("episode_number")] = "https://image.tmdb.org/t/p/w780$path"
            }
            synchronized(stillCache) { stillCache[ck] = out }
            out
        } catch (_: Exception) { emptyMap() }
    }

    /* ───────────── TVmaze episode stills (free, no API key) ───────────── */

    private val tvmazeIds = HashMap<String, Int>()
    private val tvmazeMiss = HashSet<String>()
    private val tvmazeEps = HashMap<Int, Map<Pair<Int, Int>, String>>()

    /**
     * Real episode images for one season from TVmaze (https://api.tvmaze.com), keyed by episode number.
     * The show is accepted only when its TVmaze name equals the provider title (exact match after normalising),
     * so an image is never attached to the wrong series. One request per show (all seasons) is cached.
     */
    suspend fun tvmazeStills(title: String, season: Int): Map<Int, String> = withContext(Dispatchers.IO) {
        if (season <= 0) return@withContext emptyMap()
        val clean = title.replace(Regex("""[\[(].*?[\])]"""), " ").replace(Regex("""\s+"""), " ").trim()
        val want = norm(clean)
        if (want.isEmpty()) return@withContext emptyMap()
        fun getArr(url: String): JSONArray? = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) JSONArray(r.body!!.string()) else null
        }
        try {
            if (synchronized(tvmazeIds) { want in tvmazeMiss }) return@withContext emptyMap()
            val id = synchronized(tvmazeIds) { tvmazeIds[want] } ?: run {
                val res = getArr("https://api.tvmaze.com/search/shows?q=${Uri.encode(clean)}")
                var found: Int? = null
                if (res != null) for (i in 0 until minOf(res.length(), 5)) {
                    val sh = res.getJSONObject(i).optJSONObject("show") ?: continue
                    if (norm(str(sh, "name")) == want) { found = sh.optInt("id"); break }
                }
                // Cache hits and confirmed misses; a failed request (res == null) is retried later.
                synchronized(tvmazeIds) { if (found != null) tvmazeIds[want] = found else if (res != null) tvmazeMiss.add(want) }
                found
            } ?: return@withContext emptyMap()
            val all = synchronized(tvmazeEps) { tvmazeEps[id] } ?: run {
                val eps = getArr("https://api.tvmaze.com/shows/$id/episodes") ?: return@withContext emptyMap()
                val m = HashMap<Pair<Int, Int>, String>()
                for (i in 0 until eps.length()) {
                    val e = eps.getJSONObject(i)
                    val img = e.optJSONObject("image") ?: continue
                    val url = str(img, "original").ifBlank { str(img, "medium") }
                    if (url.isNotBlank()) m[e.optInt("season") to e.optInt("number")] = url.replace("http://", "https://")
                }
                synchronized(tvmazeEps) { tvmazeEps[id] = m }
                m
            }
            all.filterKeys { it.first == season }.mapKeys { it.key.second }
        } catch (_: Exception) { emptyMap() }
    }

    /** Episode stills for a season: TVmaze (no key needed); TMDB fills in only when the user entered a TMDB key. */
    suspend fun episodeStills(title: String, season: Int): Map<Int, String> {
        val tmdb = if (store.tmdbKey.isNotBlank()) tmdbStills(title, season) else emptyMap()
        return tmdb + tvmazeStills(title, season)
    }

    /* ───────────── Health probe ───────────── */

    private fun looksLike(buf: ByteArray, n: Int): String {
        if (n <= 0) return S(R.string.a_26614bfb)
        val s = String(buf, 0, n, Charsets.ISO_8859_1)
        return when {
            s.startsWith("#EXTM3U") -> "HLS"
            buf[0] == 0x47.toByte() -> "MPEG-TS"
            s.startsWith("<") -> S(R.string.a_23045f8c)
            s.startsWith("{") -> S(R.string.a_91ed297f)
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

    /** One-line account status from the server (null when the login is not Xtream or the server did not answer). */
    suspend fun accountInfo(): String? = withContext(Dispatchers.IO) {
        val (b, u, p) = xt() ?: return@withContext null
        try {
            val ui = JSONObject(fetch("$b/player_api.php?username=${Uri.encode(u)}&password=${Uri.encode(p)}")).optJSONObject("user_info")
                ?: return@withContext null
            val exp = str(ui, "exp_date").toLongOrNull()
                ?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it * 1000)) } ?: S(R.string.a_5b4b0d88)
            S(R.string.x_acct_line, str(ui, "status"), exp, str(ui, "active_cons"), str(ui, "max_connections"))
        } catch (_: Exception) { null }
    }

    /** Self-test: account status and connection limit (the stream-level probes live in HealthChecker). */
    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        val (b, u, p) = xt() ?: return@withContext S(R.string.a_381f58fe)
        val sb = StringBuilder()
        try {
            val ui = JSONObject(fetch("$b/player_api.php?username=${Uri.encode(u)}&password=${Uri.encode(p)}")).optJSONObject("user_info")
            if (ui == null) sb.append(S(R.string.a_bee01191)) else {
                val exp = str(ui, "exp_date").toLongOrNull()
                    ?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it * 1000)) } ?: S(R.string.a_5b4b0d88)
                sb.append(S(R.string.x_diag_acct, str(ui, "status"), str(ui, "auth"), exp))
                sb.append(S(R.string.x_diag_cons, str(ui, "active_cons"), str(ui, "max_connections")))
            }
        } catch (e: Exception) { sb.append(S(R.string.x_diag_acct_failed, e.message ?: "")) }
        val live = cacheFile.takeIf { it.exists() }?.let { M3uParser.parse(it.readText()).firstOrNull()?.url }
        if (live == null) sb.append(S(R.string.a_ee94ac8e)) else {
            val stem = live.removeSuffix(".m3u8").removeSuffix(".ts")
            val ts = probeStream("$stem.ts")
            val hls = probeStream("$stem.m3u8")
            sb.append(S(R.string.x_diag_live, ts.state, ts.latencyMs, hls.state, hls.latencyMs))
            if (hls.state != Health.Dead && ts.state == Health.Dead) {
                liveExt = "m3u8"; cacheFile.delete()
                sb.append(S(R.string.a_a1845b79))
            } else if (ts.state != Health.Dead && hls.state == Health.Dead) liveExt = "ts"
            if (ts.state == Health.Dead && hls.state == Health.Dead)
                sb.append(S(R.string.a_eb16298d))
        }
        sb.toString()
    }
}

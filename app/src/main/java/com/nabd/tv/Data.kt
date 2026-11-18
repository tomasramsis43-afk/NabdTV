package com.nabd.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import android.net.Uri
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.concurrent.TimeUnit

data class Channel(
    val id: Int, val name: String, val url: String, val logo: String?, val group: String,
    val tvgId: String? = null
)

data class Prog(val start: Long, val stop: Long, val title: String)

data class Episode(val season: Int, val num: Int, val title: String, val url: String)

/** Streaming XMLTV parser: keeps only wanted channels and a small time window (low memory). */
object EpgParser {
    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US) }

    private fun time(s: String?): Long = try {
        var t = s!!.trim()
        if (t.length == 14) t += " +0000"
        fmt.get()!!.parse(t)!!.time
    } catch (e: Exception) { 0L }

    fun parse(input: InputStream, wanted: Set<String>, from: Long, to: Long): Map<String, List<Prog>> {
        val map = HashMap<String, MutableList<Prog>>()
        val p = Xml.newPullParser()
        p.setInput(input, null)
        var ch: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "programme" -> {
                        ch = p.getAttributeValue(null, "channel")
                        start = time(p.getAttributeValue(null, "start"))
                        stop = time(p.getAttributeValue(null, "stop"))
                        title = null
                    }
                    "title" -> if (ch != null && title == null) title = p.nextText()
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "programme") {
                val c = ch
                val t = title
                if (c != null && t != null && c in wanted && stop > from && start < to)
                    map.getOrPut(c) { ArrayList() }.add(Prog(start, stop, t))
                ch = null
            }
            ev = p.next()
        }
        return map.mapValues { e -> e.value.sortedBy { it.start } }
    }
}

object M3uParser {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")
    private val headerEpg = Regex("""url-tvg="([^"]+)"""")

    fun epgFromHeader(text: String): String? =
        headerEpg.find(text.take(2000))?.groupValues?.get(1)?.split(',')?.firstOrNull()?.trim()

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>(2048)
        var name = ""
        var logo: String? = null
        var group = "Other"
        var tvgId: String? = null
        var pending = false
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    val a = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    logo = a["tvg-logo"]?.takeIf { it.isNotBlank() }
                    tvgId = a["tvg-id"]?.takeIf { it.isNotBlank() }
                    group = a["group-title"]?.takeIf { it.isNotBlank() } ?: "Other"
                    name = line.substring(line.lastIndexOf('"') + 1).substringAfter(',', "").trim().ifEmpty { a["tvg-name"] ?: "Channel" }
                    pending = true
                }
                line.isNotEmpty() && !line.startsWith("#") && pending -> {
                    out += Channel(out.size, name, line, logo, group, tvgId)
                    pending = false
                }
            }
        }
        return out
    }
}

/** User-Agent that the provider accepted (shared with the video player). */
@Volatile var httpUa = "IPTVSmartersPro"

class Repo(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("nabd", Context.MODE_PRIVATE)
    private val cacheFile get() = File(ctx.filesDir, "playlist.m3u")
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .build()

    init { prefs.getString("ua", null)?.let { httpUa = it } }

    var playlistUrl: String
        get() = prefs.getString("url", "") ?: ""
        set(v) = prefs.edit().putString("url", v).apply()

    var favorites: Set<String>
        get() = prefs.getStringSet("fav", emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet("fav", v).apply()

    var epgUrl: String
        get() = prefs.getString("epg", "") ?: ""
        set(v) = prefs.edit().putString("epg", v).apply()

    var epgManual: Boolean
        get() = prefs.getBoolean("epgManual", false)
        set(v) = prefs.edit().putBoolean("epgManual", v).apply()

    /** Downloads the XMLTV guide (refresh every 6h) and returns now/next data per tvg-id. */
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
                val wanted = channels.mapNotNull { it.tvgId }.toSet()
                val now = System.currentTimeMillis()
                f.inputStream().buffered().use { raw ->
                    raw.mark(2); val b1 = raw.read(); val b2 = raw.read(); raw.reset()
                    val src = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw) else raw
                    EpgParser.parse(src, wanted, now - 2 * 3600_000L, now + 24 * 3600_000L)
                }
            } catch (e: Exception) { emptyMap() }
        }

    var xtream: String
        get() = prefs.getString("xt", "") ?: ""
        set(v) = prefs.edit().putString("xt", v).apply()

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

    /** Xtream player_api.php (lighter and less blocked than get.php) → M3U text. */
    private fun loadXtream(): String {
        val (base, user, pass) = xtream.split("\n")
        val q = "username=${Uri.encode(user)}&password=${Uri.encode(pass)}"
        val cats = JSONArray(fetch("$base/player_api.php?$q&action=get_live_categories"))
        val names = HashMap<String, String>()
        for (i in 0 until cats.length()) cats.getJSONObject(i).let { names[str(it, "category_id")] = str(it, "category_name") }
        val streams = JSONArray(fetch("$base/player_api.php?$q&action=get_live_streams"))
        fun a(x: String) = x.replace('"', '\'').replace('\n', ' ')
        val sb = StringBuilder("#EXTM3U\n")
        for (i in 0 until streams.length()) {
            val s = streams.getJSONObject(i)
            val id = str(s, "stream_id")
            if (id.isBlank()) continue
            sb.append("#EXTINF:-1 tvg-id=\"").append(a(str(s, "epg_channel_id")))
                .append("\" tvg-logo=\"").append(a(str(s, "stream_icon")))
                .append("\" group-title=\"").append(a(names[str(s, "category_id")] ?: "Other"))
                .append("\",").append(a(str(s, "name"))).append('\n')
            sb.append("$base/live/$user/$pass/$id.$liveExt\n")
        }
        return sb.toString()
    }

    var liveExt: String
        get() = prefs.getString("liveExt", "ts") ?: "ts"
        set(v) = prefs.edit().putString("liveExt", v).apply()

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

    /** Tries one stream URL with each User-Agent; reports HTTP code + what came back. */
    private fun probe(url: String, label: String, sb: StringBuilder): Boolean {
        val c = http.newBuilder().readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
        for (agent in agents) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", agent).header("Range", "bytes=0-2047").build()
                c.newCall(req).execute().use { r ->
                    val buf = ByteArray(64)
                    val n = if (r.isSuccessful) r.body!!.byteStream().read(buf) else 0
                    val kind = looksLike(buf, n)
                    sb.append("$label [${agent.take(12)}]: HTTP ${r.code} · $kind\n")
                    if (r.isSuccessful && (kind == "HLS" || kind == "MPEG-TS" || kind == "بيانات")) {
                        httpUa = agent; prefs.edit().putString("ua", agent).apply()
                        return true
                    }
                }
            } catch (e: Exception) {
                sb.append("$label [${agent.take(12)}]: ${e.javaClass.simpleName}\n")
            }
        }
        return false
    }

    /** Self-test: account status, connection limit, and whether streams really open. */
    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        val (b, u, p) = xt() ?: return@withContext "الدخول ليس بـ Xtream"
        val sb = StringBuilder()
        try {
            val ui = JSONObject(fetch("$b/player_api.php?username=${Uri.encode(u)}&password=${Uri.encode(p)}")).optJSONObject("user_info")
            if (ui == null) sb.append("لا توجد بيانات حساب في رد السيرفر\n") else {
                val exp = str(ui, "exp_date").toLongOrNull()
                    ?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(it * 1000)) } ?: "غير محدود"
                sb.append("الحساب: ${str(ui, "status")} (auth=${str(ui, "auth")}) · ينتهي: $exp\n")
                sb.append("الاتصالات الآن: ${str(ui, "active_cons")} من ${str(ui, "max_connections")}\n")
            }
        } catch (e: Exception) { sb.append("فحص الحساب فشل: ${e.message}\n") }

        val live = cacheFile.takeIf { it.exists() }?.let { M3uParser.parse(it.readText()).firstOrNull()?.url }
        if (live == null) sb.append("لا توجد قنوات محمّلة للفحص\n") else {
            val stem = live.removeSuffix(".m3u8").removeSuffix(".ts")
            val okTs = probe("$stem.ts", "مباشر ts", sb)
            val okHls = probe("$stem.m3u8", "مباشر m3u8", sb)
            if (okHls && !okTs) {
                liveExt = "m3u8"; cacheFile.delete()
                sb.append("→ تم اعتماد m3u8. اضغط تحديث في قسم المباشر.\n")
            } else if (okTs && !okHls) liveExt = "ts"
            if (!okTs && !okHls) sb.append("→ القنوات المباشرة لا تفتح: راجع الاتصالات المتزامنة والاشتراك، أو أن المزوّد يحظر الجهاز/الـ IP.\n")
        }
        val movie = File(ctx.filesDir, "movies.m3u").takeIf { it.exists() }?.let { M3uParser.parse(it.readText()).firstOrNull()?.url }
        if (movie != null) probe(movie, "فيلم", sb) else sb.append("الأفلام لم تُحمّل بعد (افتح قسم الأفلام أولاً)\n")
        sb.toString()
    }

    val hasXtream: Boolean get() = xt() != null

    private fun xt(): Triple<String, String, String>? {
        val p = xtream.split("\n")
        return if (p.size == 3) Triple(p[0], p[1], p[2]) else null
    }

    private fun api(action: String, extra: String = ""): String {
        val (b, u, p) = xt()!!
        return fetch("$b/player_api.php?username=${Uri.encode(u)}&password=${Uri.encode(p)}&action=$action$extra")
    }

    private fun categories(action: String): Map<String, String> {
        val m = HashMap<String, String>()
        try {
            val c = JSONArray(api(action))
            for (i in 0 until c.length()) c.getJSONObject(i).let { m[str(it, "category_id")] = str(it, "category_name") }
        } catch (_: Exception) { }
        return m
    }

    private fun buildM3u(arr: JSONArray, names: Map<String, String>, icon: String, url: (JSONObject) -> String?): String {
        fun a(x: String) = x.replace('"', '\'').replace('\n', ' ')
        val sb = StringBuilder("#EXTM3U\n")
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val u = url(s) ?: continue
            sb.append("#EXTINF:-1 tvg-logo=\"").append(a(str(s, icon)))
                .append("\" group-title=\"").append(a(names[str(s, "category_id")] ?: "Other"))
                .append("\",").append(a(str(s, "name"))).append('\n').append(u).append('\n')
        }
        return sb.toString()
    }

    fun clearVod() {
        File(ctx.filesDir, "movies.m3u").delete()
        File(ctx.filesDir, "series.m3u").delete()
    }

    suspend fun loadMovies(force: Boolean): List<Channel> = withContext(Dispatchers.IO) {
        val (b, u, p) = xt() ?: return@withContext emptyList()
        val f = File(ctx.filesDir, "movies.m3u")
        if (!force && f.exists()) return@withContext M3uParser.parse(f.readText())
        val names = categories("get_vod_categories")
        val t = buildM3u(JSONArray(api("get_vod_streams")), names, "stream_icon") { s ->
            val id = str(s, "stream_id")
            if (id.isBlank()) null else "$b/movie/$u/$p/$id.${str(s, "container_extension").ifBlank { "mp4" }}"
        }
        f.writeText(t)
        M3uParser.parse(t)
    }

    suspend fun loadSeries(force: Boolean): List<Channel> = withContext(Dispatchers.IO) {
        if (xt() == null) return@withContext emptyList()
        val f = File(ctx.filesDir, "series.m3u")
        if (!force && f.exists()) return@withContext M3uParser.parse(f.readText())
        val names = categories("get_series_categories")
        val t = buildM3u(JSONArray(api("get_series")), names, "cover") { s ->
            val id = str(s, "series_id")
            if (id.isBlank()) null else "series:$id"
        }
        f.writeText(t)
        M3uParser.parse(t)
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
                out += Episode(
                    k.toIntOrNull() ?: 0, str(e, "episode_num").toIntOrNull() ?: (i + 1),
                    str(e, "title"), "$b/series/$u/$p/$id.$ext"
                )
            }
        }
        out.sortedWith(compareBy({ it.season }, { it.num }))
    }

    /** Uses the local cache for instant startup; force = re-download. */
    suspend fun load(force: Boolean): List<Channel> = withContext(Dispatchers.IO) {
        if (!force && cacheFile.exists()) return@withContext M3uParser.parse(cacheFile.readText())
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
        if (!epgManual) M3uParser.epgFromHeader(text)?.let { epgUrl = it }
        list
    }
}

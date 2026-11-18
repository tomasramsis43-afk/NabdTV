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
                if (r.isSuccessful) { httpUa = agent; return r.body!!.string() }
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
            sb.append("$base/live/$user/$pass/$id.ts\n")
        }
        return sb.toString()
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

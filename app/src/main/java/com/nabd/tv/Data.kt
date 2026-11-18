package com.nabd.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Xml
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
                        val req = Request.Builder().url(epgUrl).header("User-Agent", "Mozilla/5.0 NabdTV").build()
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

    /** Uses the local cache for instant startup; force = re-download. */
    suspend fun load(force: Boolean): List<Channel> = withContext(Dispatchers.IO) {
        if (!force && cacheFile.exists()) return@withContext M3uParser.parse(cacheFile.readText())
        val url = playlistUrl
        require(url.isNotBlank()) { "No playlist URL" }
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 NabdTV").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val body = r.body!!.string()
            val list = M3uParser.parse(body)
            if (list.isEmpty()) error("Empty or invalid playlist")
            cacheFile.writeText(body)
            if (!epgManual) M3uParser.epgFromHeader(body)?.let { epgUrl = it }
            list
        }
    }
}

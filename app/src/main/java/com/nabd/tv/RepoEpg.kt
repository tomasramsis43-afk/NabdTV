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

/** Guide (XMLTV) download, parsing cache and channel matching. Extension functions of [Repo], moved out of Repo.kt unchanged in behaviour. */
/* ───────────── EPG ───────────── */

/**
 * Downloads the XMLTV guide (refresh every 6h), auto-matches it to the channels and returns
 * programmes keyed by channel url. The window covers past days (for catch-up) and the next 36 hours.
 */
/** Identifies what the parsed EPG depends on: the file, the channel list and the manual matches. */
private fun Repo.epgSignature(f: File, channels: List<Channel>): String {
    var h = 1125899906842597L
    for (c in channels) { h = 31 * h + c.url.hashCode(); h = 31 * h + (c.tvgId?.hashCode() ?: 0); h = 31 * h + c.name.hashCode() }
    return "${f.lastModified()}:${f.length()}:$h:${store.epgOverrides().hashCode()}"
}

private fun Repo.writeEpgStr(o: java.io.DataOutputStream, s: String) { val b = s.toByteArray(Charsets.UTF_8); o.writeInt(b.size); o.write(b) }
private fun Repo.readEpgStr(i: java.io.DataInputStream): String { val b = ByteArray(i.readInt()); i.readFully(b); return String(b, Charsets.UTF_8) }

private fun Repo.writeEpgCache(f: File, sig: String, out: Map<String, List<Prog>>) {
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

private fun Repo.readEpgCache(f: File, sig: String): Map<String, List<Prog>>? {
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

suspend fun Repo.loadEpg(channels: List<Channel>, force: Boolean): Map<String, List<Prog>> =
    withContext(Dispatchers.IO) {
        if (!store.epgEnabled) return@withContext emptyMap()
        try {
            val f = epgXml()
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
            val cacheFile = epgParsed()
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


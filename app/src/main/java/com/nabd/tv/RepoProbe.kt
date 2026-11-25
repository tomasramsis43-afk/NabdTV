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

/** Stream health probe and account / connection diagnostics. Extension functions of [Repo]. */
/* ───────────── Health probe ───────────── */

private fun Repo.looksLike(buf: ByteArray, n: Int): String {
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
fun Repo.probeStream(url: String): HealthRecord {
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
suspend fun Repo.accountInfo(): String? = withContext(Dispatchers.IO) {
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
suspend fun Repo.diagnose(): String = withContext(Dispatchers.IO) {
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
    val live = cacheFile.takeIf { it.exists() }?.let { M3uParser.parseFile(it).firstOrNull()?.url }
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

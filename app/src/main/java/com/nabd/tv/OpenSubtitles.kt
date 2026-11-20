package com.nabd.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class OsResult(val fileId: Long, val label: String, val lang: String, val downloads: Int)

/**
 * Online subtitle search / download through the OpenSubtitles.com REST API.
 * Needs the user's own API key plus an account (the download endpoint requires a login token).
 */
object OpenSubtitles {
    private const val HOST = "api.opensubtitles.com"
    private const val UA = "NabdTV v2"
    const val LANGS = "ar,en"
    private var token: String? = null
    private var tokenFor: String = ""
    private val JSON = "application/json".toMediaType()

    private fun req(url: String, key: String) = Request.Builder().url(url)
        .header("Api-Key", key).header("User-Agent", UA).header("Accept", "application/json")

    /** Removes years, quality tags and episode markers so the title searches well. */
    fun cleanQuery(raw: String): String = raw
        .replace(Regex("[\\[(].*?[\\])]"), " ")
        .replace(Regex("\\s+\u0645\\d+\\s*\u062D\\d+.*"), " ")
        .replace(Regex("(?i)\\b(2160p|1080p|720p|480p|4k|uhd|fhd|hd|web[- ]?dl|webrip|bluray|brrip|x264|x265|hevc|multi|dubbed|arabic)\\b"), " ")
        .replace(Regex("[|:_]+"), " ")
        .replace(Regex("\\s+"), " ").trim()

    suspend fun search(http: OkHttpClient, key: String, query: String, season: Int?, episode: Int?): List<OsResult> =
        withContext(Dispatchers.IO) {
            // Query parameters must be lowercase and sorted alphabetically for this API.
            val b = HttpUrl.Builder().scheme("https").host(HOST).addPathSegments("api/v1/subtitles")
            if (season != null && episode != null) b.addQueryParameter("episode_number", episode.toString())
            b.addQueryParameter("languages", LANGS)
                .addQueryParameter("order_by", "download_count")
                .addQueryParameter("order_direction", "desc")
                .addQueryParameter("query", query.lowercase())
            if (season != null && episode != null) b.addQueryParameter("season_number", season.toString())
            http.newCall(req(b.build().toString(), key).build()).execute().use { r ->
                if (r.code == 401 || r.code == 403) error("مفتاح OpenSubtitles غير صالح")
                if (!r.isSuccessful) error("خطأ من OpenSubtitles (${r.code})")
                val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("data") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    val a = arr.getJSONObject(i).optJSONObject("attributes") ?: return@mapNotNull null
                    val f = a.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
                    OsResult(
                        f.optLong("file_id"),
                        a.optString("release").ifBlank { f.optString("file_name") }.take(70),
                        a.optString("language"), a.optInt("download_count")
                    )
                }.take(25)
            }
        }

    private fun login(http: OkHttpClient, key: String, user: String, pass: String): String {
        token?.let { if (tokenFor == key + user) return it }
        val body = JSONObject().put("username", user).put("password", pass).toString().toRequestBody(JSON)
        val t = http.newCall(req("https://$HOST/api/v1/login", key).post(body).build()).execute().use { r ->
            if (!r.isSuccessful) error("فشل تسجيل الدخول إلى OpenSubtitles (${r.code})")
            JSONObject(r.body?.string().orEmpty()).optString("token")
        }
        if (t.isBlank()) error("فشل تسجيل الدخول إلى OpenSubtitles")
        token = t; tokenFor = key + user
        return t
    }

    suspend fun download(
        http: OkHttpClient, ctx: Context, key: String, user: String, pass: String,
        fileId: Long, mediaKey: String, title: String
    ): ExternalSubtitle = withContext(Dispatchers.IO) {
        fun link(t: String): Pair<Int, String> {
            val body = JSONObject().put("file_id", fileId).put("sub_format", "srt").toString().toRequestBody(JSON)
            return http.newCall(req("https://$HOST/api/v1/download", key).header("Authorization", "Bearer $t").post(body).build())
                .execute().use { r -> r.code to if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()).optString("link") else "" }
        }
        var res = link(login(http, key, user, pass))
        if (res.first == 401) { token = null; res = link(login(http, key, user, pass)) }   // expired token: log in again once
        if (res.first == 406) error("انتهى حد التحميل اليومي في OpenSubtitles")
        if (res.first != 200 || res.second.isBlank()) error("تعذّر الحصول على رابط الترجمة (${res.first})")
        val bytes = http.newCall(Request.Builder().url(res.second).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) error("تعذّر تحميل ملف الترجمة (${r.code})")
            r.body?.bytes() ?: ByteArray(0)
        }
        SubtitleFiles.save(ctx, bytes, "$title.srt", mediaKey) ?: error("تعذّر حفظ ملف الترجمة")
    }
}

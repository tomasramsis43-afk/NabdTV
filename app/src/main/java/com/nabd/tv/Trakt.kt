package com.nabd.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class TraktDeviceCode(val deviceCode: String, val userCode: String, val url: String, val intervalSec: Int, val expiresSec: Int)

/**
 * Trakt.tv sync: links the account with the device-code flow (no typing on the TV) and marks finished
 * movies / episodes as watched. Needs the user's own Trakt API app (client id + secret).
 */
object Trakt {
    private const val BASE = "https://api.trakt.tv"
    private val JSON = "application/json".toMediaType()

    private fun call(http: OkHttpClient, store: Store, path: String, body: JSONObject, auth: Boolean): Pair<Int, String> {
        val rb = Request.Builder().url(BASE + path)
            .header("Content-Type", "application/json")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", store.traktId)
            .header("User-Agent", "NabdTV v2")
            .post(body.toString().toRequestBody(JSON))
        if (auth) rb.header("Authorization", "Bearer " + store.traktAccess)
        return http.newCall(rb.build()).execute().use { r -> r.code to (r.body?.string().orEmpty()) }
    }

    suspend fun deviceCode(http: OkHttpClient, store: Store): TraktDeviceCode = withContext(Dispatchers.IO) {
        if (store.traktId.isBlank() || store.traktSecret.isBlank()) error(S(R.string.a_4df3672b))
        val (code, text) = call(http, store, "/oauth/device/code", JSONObject().put("client_id", store.traktId), auth = false)
        if (code != 200) error("تعذّر بدء الربط مع Trakt ($code)")
        val j = JSONObject(text)
        TraktDeviceCode(j.getString("device_code"), j.getString("user_code"), j.optString("verification_url", "https://trakt.tv/activate"),
            j.optInt("interval", 5), j.optInt("expires_in", 600))
    }

    /** Polls until the user approves the code on trakt.tv/activate. Returns true when the account is linked. */
    suspend fun awaitAuthorization(http: OkHttpClient, store: Store, dc: TraktDeviceCode): Boolean = withContext(Dispatchers.IO) {
        var waited = 0
        var interval = dc.intervalSec.coerceAtLeast(3)
        while (waited < dc.expiresSec) {
            delay(interval * 1000L); waited += interval
            val body = JSONObject().put("code", dc.deviceCode).put("client_id", store.traktId).put("client_secret", store.traktSecret)
            val (code, text) = call(http, store, "/oauth/device/token", body, auth = false)
            when (code) {
                200 -> { saveTokens(store, JSONObject(text)); return@withContext true }
                400 -> {}                       // still waiting for the user
                429 -> interval += 2            // asked to slow down
                404, 409, 410, 418 -> return@withContext false
                else -> {}
            }
        }
        false
    }

    private fun saveTokens(store: Store, j: JSONObject) {
        store.traktAccess = j.optString("access_token")
        store.traktRefresh = j.optString("refresh_token")
        store.traktExpiresAt = (j.optLong("created_at", System.currentTimeMillis() / 1000) + j.optLong("expires_in", 7_776_000L)) * 1000L
    }

    private fun refreshIfNeeded(http: OkHttpClient, store: Store) {
        if (store.traktRefresh.isBlank() || System.currentTimeMillis() < store.traktExpiresAt - 86_400_000L) return
        val body = JSONObject().put("refresh_token", store.traktRefresh).put("client_id", store.traktId)
            .put("client_secret", store.traktSecret).put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob").put("grant_type", "refresh_token")
        val (code, text) = call(http, store, "/oauth/token", body, auth = false)
        if (code == 200) saveTokens(store, JSONObject(text))
    }

    fun linked(store: Store) = store.traktAccess.isNotBlank()

    fun unlink(store: Store) { store.traktAccess = ""; store.traktRefresh = ""; store.traktExpiresAt = 0L }

    private fun iso(t: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

    /** Trakt answers 200 even when it could not match the title; the miss is listed under "not_found". */
    private fun notFound(body: String): Boolean = runCatching {
        val nf = JSONObject(body).optJSONObject("not_found") ?: return@runCatching false
        listOf("movies", "shows", "seasons", "episodes").any { (nf.optJSONArray(it)?.length() ?: 0) > 0 }
    }.getOrDefault(false)

    private fun yearOf(name: String): Int? = Regex("\\b(19|20)\\d{2}\\b").find(name)?.value?.toIntOrNull()

    /** Marks a movie as watched (matched by title / year). */
    suspend fun markMovie(http: OkHttpClient, store: Store, name: String, at: Long): Boolean = withContext(Dispatchers.IO) {
        if (!linked(store)) return@withContext false
        refreshIfNeeded(http, store)
        val m = JSONObject().put("title", OpenSubtitles.cleanQuery(name)).put("watched_at", iso(at))
        yearOf(name)?.let { m.put("year", it) }
        val (code, body) = call(http, store, "/sync/history", JSONObject().put("movies", JSONArray().put(m)), auth = true)
        code in 200..299 && !notFound(body)
    }

    /** Marks one episode as watched (show matched by title). */
    suspend fun markEpisode(http: OkHttpClient, store: Store, show: String, season: Int, episode: Int, at: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (!linked(store)) return@withContext false
            refreshIfNeeded(http, store)
            val ep = JSONObject().put("number", episode).put("watched_at", iso(at))
            val s = JSONObject().put("number", season).put("episodes", JSONArray().put(ep))
            val sh = JSONObject().put("title", OpenSubtitles.cleanQuery(show)).put("seasons", JSONArray().put(s))
            yearOf(show)?.let { sh.put("year", it) }
            val (code, body) = call(http, store, "/sync/history", JSONObject().put("shows", JSONArray().put(sh)), auth = true)
            code in 200..299 && !notFound(body)
        }
}

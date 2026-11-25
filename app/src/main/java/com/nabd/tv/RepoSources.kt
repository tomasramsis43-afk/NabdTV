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

/** Saved provider logins (switch between providers without typing the login again). Extension functions of [Repo]. */
/* ───────── saved sources (switch between providers without typing the login again) ───────── */
private fun Repo.sourceId(kind: String, a: String, user: String) = Integer.toHexString((kind + "|" + a + "|" + user).hashCode())

fun Repo.savedSources(): List<SavedSource> {
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

private fun Repo.writeSources(list: List<SavedSource>) {
    val a = org.json.JSONArray()
    list.forEach { a.put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind).put("a", it.address).put("user", it.user).put("pass", it.pass)) }
    val e = Secret.encOrNull(a.toString()) ?: run { Diag.note("RepoSources.writeSources", Secret.SecretException("encrypt failed")); return }
    prefs.edit().putString("sources", e).apply()
}

/** Adds (or refreshes) a source in the saved list. [address] is the Xtream host or the playlist URL. */
fun Repo.rememberSource(kind: String, address: String, user: String = "", pass: String = "") {
    if (address.isBlank()) return
    val host = runCatching { java.net.URI(address).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: address.take(40)
    val src = SavedSource(sourceId(kind, address, user), if (kind == "xtream" && user.isNotBlank()) "$host · $user" else host, kind, address, user, pass)
    writeSources(listOf(src) + savedSources().filter { it.id != src.id })
}

fun Repo.forgetSource(id: String) = writeSources(savedSources().filter { it.id != id })

/** The login currently in use as a saved source (older installs never saved theirs). */
fun Repo.currentSourceId(): String? {
    xt()?.let { (h, u, _) -> return sourceId("xtream", h, u) }
    return playlistUrl.takeIf { it.isNotBlank() }?.let { sourceId("m3u", it, "") }
}

fun Repo.ensureCurrentSaved() {
    val id = currentSourceId() ?: return
    if (savedSources().any { it.id == id }) return
    xt()?.let { (h, u, p) -> rememberSource("xtream", h, u, p) } ?: rememberSource("m3u", playlistUrl)
}

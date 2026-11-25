package com.nabd.tv

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Functions of [AppViewModel] (moved out of AppViewModel.kt unchanged in behaviour; the state they use stays in the ViewModel). */
/* ───────── home shelves ───────── */
internal fun AppViewModel.histChannel(h: HistoryEntry) = Channel(-1, h.name, h.url, h.logo, h.group)

/** Loads the TMDB charts (cached 6 h). Call again with [force] after the key changes. */
fun AppViewModel.refreshTmdbCharts(force: Boolean = false) {
    val key = store.tmdbKey
    if (key.isBlank()) { tmdbCharts = null; return }
    if (!force && tmdbCharts != null && System.currentTimeMillis() - chartsAt < 6 * 3_600_000L) return
    viewModelScope.launch {
        val c = withContext(Dispatchers.IO) { runCatching { TmdbCharts.load(key) }.getOrNull() }
        if (c != null) { tmdbCharts = c; chartsAt = System.currentTimeMillis(); TmdbCharts.save(ctx, c, chartsAt) }
        else if (force) tmdbCharts = null
    }
}

/** Loads the "coming soon" lists (cached 6 h). Call again with [force] after the key changes. */
fun AppViewModel.refreshUpcoming(force: Boolean = false) {
    val key = store.tmdbKey
    if (key.isBlank()) { upcomingRaw = emptyList(); return }
    if (!force && upcomingRaw.isNotEmpty() && System.currentTimeMillis() - upcomingAt < 6 * 3_600_000L) return
    viewModelScope.launch {
        val l = withContext(Dispatchers.IO) { runCatching { Upcoming.load(key, Lang.code) }.getOrNull() }
        if (l != null) { upcomingRaw = l; upcomingAt = System.currentTimeMillis(); Upcoming.save(ctx, l, upcomingAt) }
        else if (force) upcomingRaw = emptyList()
    }
}

suspend fun AppViewModel.loadHomeInfo(c: Channel) {
    if (homeInfoUrl == c.url) return
    homeInfoUrl = c.url; homeInfo = null
    val k = kindOf(c)
    if (k != Kind.Live) homeInfo = runCatching { repo.vodInfo(c, k) }.getOrNull()
}

/** Poster + backdrop for [c] from caches / TMDB only (never hits the provider's API), for the adaptive image system. */
suspend fun AppViewModel.artworkFor(c: Channel, kind: Kind): Pair<String?, String?> = withContext(Dispatchers.IO) {
    val info = repo.cachedInfo(c, kind)
    val year = info?.year?.toIntOrNull()
    val backdrop = info?.backdrop ?: runCatching { Artwork.backdrop(c.name, kind, year)?.url }.getOrNull()
    val poster = info?.poster ?: runCatching { Artwork.poster(c.name, kind, year)?.url }.getOrNull()
    poster to backdrop
}

/**
 * Home recommendations. The last result is kept on the device and shown at once on the next launch while the fresh
 * one is computed in the background. Only plain values are read off the main thread (raw lists + a snapshot of
 * history / favourites), never derived Compose state.
 */
internal fun AppViewModel.refreshRecs() {
    val a = all; val m = movies; val sr = series
    val h = history; val f = favs; val lk = liked; val dl = disliked
    viewModelScope.launch {
        val file = recsFile
        if (recs.isEmpty() && because == null) {
            val restored = withContext(Dispatchers.Default) { runCatching { readRecsSnapshot(file, a + m + sr) }.getOrNull() }
            if (restored != null && recs.isEmpty() && because == null) { recs = restored.first; because = restored.second }
        }
        val (r, b) = withContext(Dispatchers.Default) {
            val pool = (a + m + sr).filter { parental.visible(it) }
            Recommender.recommend(pool, h, f, 24, liked = lk, disliked = dl) to Recommender.becauseYouWatched(pool, h, 20)
        }
        recs = r; because = b
        withContext(Dispatchers.IO) { runCatching { writeRecsSnapshot(file, r, b) } }
    }
}

internal fun AppViewModel.writeRecsSnapshot(file: java.io.File, r: List<Channel>, b: Pair<String, List<Channel>>?) {
    val o = org.json.JSONObject()
    o.put("recs", org.json.JSONArray(r.map { it.url }))
    if (b != null) { o.put("becauseTitle", b.first); o.put("because", org.json.JSONArray(b.second.map { it.url })) }
    file.writeText(o.toString())
}

internal fun AppViewModel.readRecsSnapshot(file: java.io.File, pool: List<Channel>): Pair<List<Channel>, Pair<String, List<Channel>>?>? {
    if (!file.exists() || pool.isEmpty()) return null
    val o = org.json.JSONObject(file.readText())
    val byUrl = HashMap<String, Channel>(pool.size * 2)
    for (c in pool) byUrl[c.url] = c
    fun urls(key: String): List<Channel> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { byUrl[arr.optString(it)] }.filter { parental.visible(it) }
    }
    val r = urls("recs")
    val bl = urls("because")
    val bt = o.optString("becauseTitle")
    return r to (if (bl.isNotEmpty() && bt.isNotBlank()) bt to bl else null)
}

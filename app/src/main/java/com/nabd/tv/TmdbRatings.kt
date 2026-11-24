package com.nabd.tv

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import java.util.Locale
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The 0-10 rating shown on posters comes from TMDB (the verified match for the title), never from the provider's own
 * number. Found once per title and kept on the device, so cards show it immediately on later launches.
 */
object TmdbRatings {
    private val map = ConcurrentHashMap<String, String>()
    private val holders = HashMap<String, MutableState<String?>>()
    private val tried = ConcurrentHashMap.newKeySet<String>()
    private val gate = kotlinx.coroutines.sync.Semaphore(3)   // at most 3 rating lookups at once
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: android.content.Context) {
        val p = ctx.applicationContext.getSharedPreferences("tmdb_ratings", android.content.Context.MODE_PRIVATE)
        prefs = p
        runCatching { for ((k, v) in p.all) (v as? String)?.let { map[k] = it } }
    }

    private fun holder(url: String) = holders.getOrPut(url) { mutableStateOf(map[url]) }

    /** TMDB rating for the title ("7.4"), or null while unknown. Observable: the card recomposes when it arrives. */
    fun get(url: String): String? = holder(url).value

    /** Looks the rating up once per title. Call on the main thread (e.g. LaunchedEffect); the network part runs on IO. */
    suspend fun resolve(ch: Channel) {
        val kind = kindOf(ch)
        if (kind == Kind.Live || map.containsKey(ch.url) || ch.url in tried) return
        kotlinx.coroutines.delay(300)   // fast scrolling: cards that leave within this time never start a request
        if (!tried.add(ch.url)) return
        val v = try {
            gate.withPermit { withContext(Dispatchers.IO) { Artwork.hit(ch.name, kind, null)?.optDouble("vote_average", 0.0) ?: 0.0 } }
        } catch (e: Exception) { tried.remove(ch.url); return }
        if (v <= 0.0) return
        val s = String.format(Locale.US, "%.1f", v)
        map[ch.url] = s
        holder(ch.url).value = s
        prefs?.edit()?.putString(ch.url, s)?.apply()
    }
}

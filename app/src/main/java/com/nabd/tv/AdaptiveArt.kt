package com.nabd.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * Adaptive images: every movie / series has up to two pictures, a portrait poster and a landscape backdrop, both tied to
 * the same id (the channel url). Screens ask for the best one for the CURRENT orientation and fall back to the other one
 * when it is missing.
 */
@Immutable
data class ArtPair(val poster: String?, val backdrop: String?, val settled: Boolean = false) {
    // [settled]: the better (TMDB) lookup for this title has finished - the pictures here are final, show them without waiting.
    /** Best picture for the orientation; the other one when it is missing. */
    fun pick(landscape: Boolean): String? = if (landscape) backdrop ?: poster else poster ?: backdrop

    /** True when [pick] returns the picture made for that orientation (not a fallback). */
    fun natural(landscape: Boolean): Boolean = if (landscape) backdrop != null else poster != null
}

/** TMDB serves the same picture in several widths; phone cards do not need the biggest (less data, decoding and heat). */
fun cardSized(url: String?): String? = when {
    url == null -> null
    "/t/p/w1280/" in url -> url.replace("/t/p/w1280/", "/t/p/w780/")   // backdrops
    "/t/p/w780/" in url -> url.replace("/t/p/w780/", "/t/p/w500/")     // posters
    else -> url
}

object ArtStore {
    private val map = HashMap<String, ArtPair>()
    private val holders = HashMap<String, androidx.compose.runtime.MutableState<ArtPair?>>()   // one state per title: only that card recomposes
    private fun holder(url: String) = holders.getOrPut(url) { androidx.compose.runtime.mutableStateOf(map[url]) }
    private val tried = ConcurrentHashMap.newKeySet<String>()
    private val order = ArrayDeque<String>()                         // oldest first, caps the store
    private const val MAX = 1500
    private var prefs: android.content.SharedPreferences? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val saver = Runnable { save() }
    private val sessionSettled = HashSet<String>()   // lookups that failed / found nothing: stop waiting now, but ask again next session

    /** Restores the pictures found in earlier sessions, so the very first frame already shows the right image (no re-loading / swapping). */
    fun init(ctx: android.content.Context) {
        val p = ctx.applicationContext.getSharedPreferences("art_store", android.content.Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            val j = org.json.JSONObject(p.getString("m", "{}") ?: "{}")
            for (k in j.keys()) {
                val e = j.getJSONObject(k)
                // Entries saved by older versions have no flag: they came from the lookup, so they count as settled (no one-time wait).
                map[k] = ArtPair(e.optString("p").ifBlank { null }, e.optString("b").ifBlank { null }, if (e.has("r")) e.optInt("r") == 1 else true)
                order.addLast(k)
            }
        }
    }

    private fun scheduleSave() { ui.removeCallbacks(saver); ui.postDelayed(saver, 2500) }

    private fun save() {
        val p = prefs ?: return
        runCatching {
            val j = org.json.JSONObject()
            for (k in order) map[k]?.let { j.put(k, org.json.JSONObject().put("p", it.poster ?: "").put("b", it.backdrop ?: "").put("r", if (it.settled && k !in sessionSettled) 1 else 0)) }
            p.edit().putString("m", j.toString()).apply()
        }
    }

    /** Known pictures for [ch]; the provider's image is the poster until something better is known. */
    fun get(ch: Channel): ArtPair = holder(ch.url).value ?: ArtPair(ch.logo, null)

    /** Merges new pictures into the pair (never replaces a known one with nothing). Call on the main thread. */
    fun put(ch: Channel, poster: String? = null, backdrop: String? = null, settle: Boolean = false) {
        val p = poster?.takeIf { it.isNotBlank() }
        val b = backdrop?.takeIf { it.isNotBlank() }
        if (p == null && b == null && !settle) return
        val old = map[ch.url] ?: ArtPair(ch.logo, null)
        val n = ArtPair(p ?: old.poster, b ?: old.backdrop, old.settled || settle)
        if (n != map[ch.url]) {
            if (ch.url !in map) {
                order.addLast(ch.url)
                while (order.size > MAX) order.removeFirst().let { map.remove(it); holders.remove(it) }
            }
            map[ch.url] = n
            holder(ch.url).value = n
            scheduleSave()
        }
    }

    /** Nothing better was found (or the lookup failed): the provider's picture is shown now, the lookup is retried in a later session. */
    private fun settleForNow(ch: Channel) {
        if (map[ch.url]?.settled == true) return
        sessionSettled.add(ch.url)
        put(ch, settle = true)
    }

    /** Looks the missing picture up once (cache first, then TMDB), without ever touching the provider's network. */
    suspend fun resolve(vm: AppViewModel, ch: Channel) {
        val kind = kindOf(ch)
        if (kind == Kind.Live || !tried.add(ch.url)) return
        val (p, b) = try { vm.artworkFor(ch, kind) } catch (e: Exception) { tried.remove(ch.url); settleForNow(ch); return }
        if (p == null && b == null) { tried.remove(ch.url); settleForNow(ch); return }   // offline / not found yet: allow a later retry
        sessionSettled.remove(ch.url)
        put(ch, p, b, settle = true)
    }
}

/**
 * Draws the best picture for [landscape] filling its box, no stretching:
 *  - the matching picture is cropped with the subject kept in view;
 *  - a portrait picture inside a wide frame is shown whole over a soft blurred copy of itself (no empty bars, no crop of faces);
 *  - a wide picture inside a tall frame is centre-cropped.
 * Switching pictures (rotation, a better image arriving) cross-fades instead of flashing.
 */
@Composable
fun AdaptiveArtImage(
    vm: AppViewModel,
    ch: Channel,
    landscape: Boolean,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    /** Cards: when only the other-orientation picture exists, fill the frame with it (sharp) instead of the blurred-fit look. */
    cropFallback: Boolean = false,
    /** Phone cards: ask TMDB for the smaller picture sizes. Hero / detail leave this off and keep full quality. */
    thumb: Boolean = false,
    /** How long to keep the frame neutral while the TMDB lookup runs (detail waits longer so the picture is not swapped). */
    maxWaitMs: Long = 3000
) {
    val art = ArtStore.get(ch)
    // The provider's thumbnail is NOT shown first and swapped for the TMDB picture a moment later: while the TMDB lookup
    // is pending the frame stays neutral, then the final picture fades in once. If TMDB has nothing (or is slow / offline)
    // the provider's picture is shown instead.
    var gaveUp by remember(ch.url) { mutableStateOf(false) }
    LaunchedEffect(ch.url) { delay(maxWaitMs); gaveUp = true }
    val waiting = vm.store.tmdbKey.isNotBlank() && !art.settled && !gaveUp
    LaunchedEffect(ch.url, landscape) {
        // Only look something up when the picture this orientation needs is missing.
        // With a TMDB key also swap provider thumbnails for the clean, sharper TMDB poster (found once, then remembered on disk).
        val sharper = vm.store.tmdbKey.isNotBlank()
        if ((landscape && art.backdrop == null) || art.poster == null || sharper) ArtStore.resolve(vm, ch)
    }
    val picked = if (waiting) null else art.pick(landscape)
    val natural = if (waiting) false else art.natural(landscape)
    // A poster used as the fallback inside a wide frame keeps its full size (it is cropped and enlarged there).
    val url = if (thumb && (!landscape || natural)) cardSized(picked) else picked
    Crossfade(targetState = url to natural, modifier = modifier, animationSpec = tween(350), label = "adaptiveArt") { (u, nat) ->
        when {
            u == null -> Box(Modifier.fillMaxSize().background(SurfHigh))
            nat || !landscape || cropFallback -> AsyncImage(
                model = u, contentDescription = null, contentScale = ContentScale.Crop,
                // Vertical bias: -1 = top of the picture, 0 = centre. Biased towards the top so faces / heads are not cut off
                // (the picture sits lower in its frame); wide pictures need it most, portrait posters a little.
                alignment = BiasAlignment(0f, if (landscape) (if (nat) -0.8f else -1f) else -0.5f),
                alpha = alpha, modifier = Modifier.fillMaxSize()
            )
            else -> Box(Modifier.fillMaxSize().background(SurfHigh)) {
                AsyncImage(
                    model = u, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(28.dp).alpha(0.45f * alpha)
                )
                AsyncImage(
                    model = u, contentDescription = null, contentScale = ContentScale.Fit,
                    alignment = Alignment.Center, alpha = alpha, modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

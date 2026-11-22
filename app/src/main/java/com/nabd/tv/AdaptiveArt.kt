package com.nabd.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.util.concurrent.ConcurrentHashMap

/**
 * Adaptive images: every movie / series has up to two pictures, a portrait poster and a landscape backdrop, both tied to
 * the same id (the channel url). Screens ask for the best one for the CURRENT orientation and fall back to the other one
 * when it is missing.
 */
@Immutable
data class ArtPair(val poster: String?, val backdrop: String?) {
    /** Best picture for the orientation; the other one when it is missing. */
    fun pick(landscape: Boolean): String? = if (landscape) backdrop ?: poster else poster ?: backdrop

    /** True when [pick] returns the picture made for that orientation (not a fallback). */
    fun natural(landscape: Boolean): Boolean = if (landscape) backdrop != null else poster != null
}

object ArtStore {
    private val map = mutableStateMapOf<String, ArtPair>()          // snapshot state: cards update when a picture arrives
    private val tried = ConcurrentHashMap.newKeySet<String>()
    private val order = ArrayDeque<String>()                         // oldest first, caps the store
    private const val MAX = 1500
    private var prefs: android.content.SharedPreferences? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val saver = Runnable { save() }

    /** Restores the pictures found in earlier sessions, so the very first frame already shows the right image (no re-loading / swapping). */
    fun init(ctx: android.content.Context) {
        val p = ctx.applicationContext.getSharedPreferences("art_store", android.content.Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            val j = org.json.JSONObject(p.getString("m", "{}") ?: "{}")
            for (k in j.keys()) {
                val e = j.getJSONObject(k)
                map[k] = ArtPair(e.optString("p").ifBlank { null }, e.optString("b").ifBlank { null })
                order.addLast(k)
            }
        }
    }

    private fun scheduleSave() { ui.removeCallbacks(saver); ui.postDelayed(saver, 2500) }

    private fun save() {
        val p = prefs ?: return
        runCatching {
            val j = org.json.JSONObject()
            for (k in order) map[k]?.let { j.put(k, org.json.JSONObject().put("p", it.poster ?: "").put("b", it.backdrop ?: "")) }
            p.edit().putString("m", j.toString()).apply()
        }
    }

    /** Known pictures for [ch]; the provider's image is the poster until something better is known. */
    fun get(ch: Channel): ArtPair = map[ch.url] ?: ArtPair(ch.logo, null)

    /** Merges new pictures into the pair (never replaces a known one with nothing). Call on the main thread. */
    fun put(ch: Channel, poster: String? = null, backdrop: String? = null) {
        val p = poster?.takeIf { it.isNotBlank() }
        val b = backdrop?.takeIf { it.isNotBlank() }
        if (p == null && b == null) return
        val old = map[ch.url] ?: ArtPair(ch.logo, null)
        val n = ArtPair(p ?: old.poster, b ?: old.backdrop)
        if (n != map[ch.url]) {
            if (ch.url !in map) {
                order.addLast(ch.url)
                while (order.size > MAX) order.removeFirst().let { map.remove(it) }
            }
            map[ch.url] = n
            scheduleSave()
        }
    }

    /** Looks the missing picture up once (cache first, then TMDB), without ever touching the provider's network. */
    suspend fun resolve(vm: AppViewModel, ch: Channel) {
        val kind = kindOf(ch)
        if (kind == Kind.Live || !tried.add(ch.url)) return
        val (p, b) = vm.artworkFor(ch, kind)
        if (p == null && b == null) tried.remove(ch.url)   // offline / not found yet: allow a later retry
        put(ch, p, b)
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
    cropFallback: Boolean = false
) {
    val art = ArtStore.get(ch)
    LaunchedEffect(ch.url, landscape) {
        // Only look something up when the picture this orientation needs is missing.
        // With a TMDB key also swap provider thumbnails for the clean, sharper TMDB poster (found once, then remembered on disk).
        val sharper = vm.store.tmdbKey.isNotBlank()
        if ((landscape && art.backdrop == null) || art.poster == null || sharper) ArtStore.resolve(vm, ch)
    }
    val url = art.pick(landscape)
    val natural = art.natural(landscape)
    Crossfade(targetState = url to natural, modifier = modifier, animationSpec = tween(350), label = "adaptiveArt") { (u, nat) ->
        when {
            u == null -> Box(Modifier.fillMaxSize().background(SurfHigh))
            nat || !landscape || cropFallback -> AsyncImage(
                model = u, contentDescription = null, contentScale = ContentScale.Crop,
                alignment = BiasAlignment(0f, if (landscape) (if (nat) -0.35f else -0.55f) else -0.2f),
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

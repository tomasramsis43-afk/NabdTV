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
internal fun AppViewModel.detailable(c: Channel) = when (kindOf(c)) { Kind.Live -> false; Kind.Series -> c.url.startsWith("series:"); Kind.Movie -> true }

internal fun AppViewModel.contextFor(c: Channel): List<Channel> {
    val cands = ArrayList<List<Channel>>()
    cands.add(visible); shelves.forEach { cands.add(it.items) }; cands.add(recs); because?.second?.let { cands.add(it) }
    return (cands.firstOrNull { l -> l.any { it.url == c.url } } ?: listOf(c)).filter { detailable(it) }
}

/** Titles next to the open one: index 0 = previous, 1 = next (null at the ends). */
fun AppViewModel.neighbors(): List<Channel?> {
    val cur = detail ?: return listOf(null, null)
    val i = neighborList.indexOfFirst { it.url == cur.url }
    if (i < 0) return listOf(null, null)
    return listOf(neighborList.getOrNull(i - 1), neighborList.getOrNull(i + 1))
}

/** Moves to the previous (-1) / next (+1) title of the list it was opened from; stops at the ends. */
fun AppViewModel.stepDetail(delta: Int): Boolean {
    val t = neighbors().getOrNull(if (delta < 0) 0 else 1) ?: return false
    showDetail(t, push = false)
    return true
}

fun AppViewModel.openDetail(c: Channel) = guard(c) { showDetail(c, push = true) }

internal fun AppViewModel.showDetail(c: Channel, push: Boolean) {
    if (screen == Screen.Detail) {
        if (push) detail?.takeIf { it.url != c.url }?.let {
            detailStack.add(it)
            if (detailStack.size > 20) detailStack.removeAt(0)
        }
    } else { detailStack.clear(); neighborList = contextFor(c) }   // opened from Home / search / a list: a fresh trail
    val k = kindOf(c)
    detail = c; info = repo.cachedInfo(c, k); episodes = emptyList(); season = 1; error = null; epFocusUrl = null; castWorks = emptyList()
    back = Screen.Main; screen = Screen.Detail
    viewModelScope.launch {
        val i = repo.vodInfo(c, k)
        if (detail?.url != c.url) return@launch   // another title was opened meanwhile: never let this one's info replace it
        info = i
        if (i != null) loadCastWorks(c, i)
    }
    if (k == Kind.Series) {
        epLoading = true
        viewModelScope.launch {
            try {
                episodes = repo.loadEpisodes(c.url.removePrefix("series:").toInt())
                season = episodes.firstOrNull()?.season ?: 1
                loadStills(c, season)
            } catch (e: Exception) { error = friendly(e) }
            epLoading = false
        }
    }
}

/** Library titles starring the first few cast members (matched by exact normalised title), most recent additions first. */
internal suspend fun AppViewModel.loadCastWorks(c: Channel, i: VodInfo) {
    val ids = i.people.filter { it.id > 0 }.take(3).map { it.id }
    if (ids.isEmpty()) return
    val titles = HashSet<String>()
    ids.forEach { titles += Artwork.filmography(it) }
    if (titles.isEmpty()) return
    val found = withContext(Dispatchers.Default) {
        val self = LogoMatch.norm(LogoMatch.clean(c.name).first)
        (movieList + seriesList).filter { x ->
            x.url != c.url && LogoMatch.norm(LogoMatch.clean(x.name).first).let { it != self && it in titles }
        }.distinctBy { LogoMatch.norm(LogoMatch.clean(it.name).first) }.take(20)
    }
    if (detail?.url == c.url) castWorks = found
}

/** Shows which artwork/metadata fields the provider really sends for one movie and one series (no credentials are shown). */
fun AppViewModel.providerCheck() {
    viewModelScope.launch {
        fun mark(b: Boolean) = if (b) "✓" else "✗"
        val lines = ArrayList<String>()
        suspend fun check(c: Channel?, k: Kind, label: String) {
            if (c == null) { lines += tx(R.string.prov_none, label); return }
            val i = runCatching { repo.vodInfo(c, k) }.getOrNull()
            lines += "$label: ${c.name.take(28)}"
            lines += tx(R.string.prov_fields, mark(!i?.poster.isNullOrBlank()), mark(!i?.backdrop.isNullOrBlank()), mark(!i?.trailer.isNullOrBlank()),
                mark(!i?.cast.isNullOrBlank()), mark(!i?.plot.isNullOrBlank()))
            if (k == Kind.Series) {
                val eps = runCatching { repo.loadEpisodes(c.url.removePrefix("series:").toInt()) }.getOrDefault(emptyList())
                lines += tx(R.string.prov_eps, eps.size, eps.count { !it.image.isNullOrBlank() })
            }
        }
        check(movieList.firstOrNull(), Kind.Movie, tx(R.string.detail_type_movie))
        check(seriesList.firstOrNull { it.url.startsWith("series:") }, Kind.Series, tx(R.string.detail_type_series))
        menu = MenuRequest(tx(R.string.prov_check_title), lines.map { it to {} })
    }
}

fun AppViewModel.closeDetail() {
    val prev = if (detailStack.isEmpty()) null else detailStack.removeAt(detailStack.size - 1)
    if (prev != null) { showDetail(prev, push = false); return }   // back to the title you came from
    restoreFocus = true; screen = Screen.Main
}

fun AppViewModel.selectSeason(n: Int) { season = n; detail?.let { loadStills(it, n) } }

/** Fetches real TMDB stills for one season (lazy: only the season on screen) and merges them into [episodes]. */
internal fun AppViewModel.loadStills(c: Channel, n: Int) {
    val k = "${c.url}:$n"
    if (!stillsRequested.add(k)) return
    viewModelScope.launch {
        val m = repo.episodeStills(c.name, n)
        if (m.isEmpty()) { stillsRequested.remove(k); return@launch }
        if (detail?.url != c.url) return@launch
        episodes = episodes.map { e -> if (e.season == n) m[e.num]?.let { e.copy(image = it) } ?: e else e }
    }
}

/** Opens the right screen for an item from any shelf or grid. */
fun AppViewModel.open(c: Channel) {
    when (kindOf(c)) {
        Kind.Live -> play(c)
        Kind.Series -> if (c.url.startsWith("series:")) openDetail(c) else play(c)
        Kind.Movie -> openDetail(c)
    }
}

/** Starts the device-code link: shows the code on screen and waits for approval on trakt.tv/activate. */
fun AppViewModel.traktLink() {
    traktJob?.cancel()
    traktJob = viewModelScope.launch {
        try {
            val dc = Trakt.deviceCode(repo.http, store)
            menu = MenuRequest(tx(R.string.trakt_code, dc.url, dc.userCode), listOf(tx(R.string.dlg_cancel) to { traktJob?.cancel() }))
            val ok = Trakt.awaitAuthorization(repo.http, store, dc)
            menu = null
            say(if (ok) tx(R.string.trakt_linked) else tx(R.string.trakt_not_linked))
        } catch (e: kotlinx.coroutines.CancellationException) {
            menu = null; throw e
        } catch (e: Exception) { menu = null; say(e.message ?: tx(R.string.trakt_conn_failed)) }
        bump()
    }
}

fun AppViewModel.traktUnlink() { Trakt.unlink(store); say(tx(R.string.trakt_unlinked)); bump() }

/** Called once when a movie / episode is finished: marks it watched on Trakt (silently ignored on failure). */
internal fun AppViewModel.traktMarkWatched(c: Channel) {
    if (!Trakt.linked(store)) return
    val now = System.currentTimeMillis()
    viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val ep = episodes.firstOrNull { it.url == c.url }
            val show = detail?.name
            if (ep != null && show != null) Trakt.markEpisode(repo.http, store, show, ep.season, ep.num, now)
            else if (kindOf(c) == Kind.Movie) Trakt.markMovie(repo.http, store, c.name, now)
        }
    }
}

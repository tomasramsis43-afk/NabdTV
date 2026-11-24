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
fun AppViewModel.play(c: Channel, mode: PlayMode? = null, startAt: Long = -1) = guard(c) {
    if (limitReached()) { pin = PinRequest(tx(R.string.pin_time_limit)) { }; return@guard }
    if (screen != Screen.Player) back = screen
    val m = mode ?: if (kindOf(c) == Kind.Live) PlayMode.Live else PlayMode.Vod
    playMode = m
    resumeAt = when {
        startAt >= 0 -> startAt
        m == PlayMode.Vod -> store.historyOf(c.url)?.takeIf { !it.finished }?.position ?: 0L
        else -> 0L
    }
    zapList = if (m == PlayMode.Live) (if (tab == Tab.Live || tab == Tab.Home) visible.takeIf { it.any { x -> x.url == c.url } } ?: liveList else liveList) else emptyList()
    tried = hashSetOf(c.url)
    current = c
    screen = Screen.Player
}

fun AppViewModel.playEpisode(s: Channel, e: Episode) {
    // Remember the episode (image, number, title) and make it the series' current episode for Continue watching.
    store.saveEpMeta(e.url, EpMeta(s.url, s.name, e.season, e.num, e.title, VmLogic.episodeImage(e, episodes, s.logo)))
    store.setContCursor(s.url, e.url)
    dropLater(s.url)
    if (detail?.url == s.url && episodes.isNotEmpty()) epListCache[s.url] = episodes
    bump()
    play(Channel(-1, "${s.name} · ${tx(R.string.ep_label, e.season, e.num)}", e.url, s.logo, s.group), PlayMode.Vod)
}

/** History from before Continue watching existed has no episode metadata: rebuild it from the entry's name. */
internal fun AppViewModel.legacyMeta(h: HistoryEntry): EpMeta? {
    if (h.kind != Kind.Series) return null
    val (name, season, num) = VmLogic.parseEpisodeName(h.name) ?: return null
    val s = uniqueSeriesByName[LogoMatch.norm(name)] ?: return null
    return EpMeta(s.url, s.name, season, num, "", null)
}

/** Landscape image for a card that has none yet: a movie's backdrop, or the episode's still (TVmaze / TMDB). */
suspend fun AppViewModel.continueImage(it: ContinueItem): String? {
    if (imageCache.containsKey(it.key + it.episodeUrl)) return imageCache[it.key + it.episodeUrl]
    val img = try {
        if (it.seriesUrl == null) repo.vodInfo(it.play, Kind.Movie)?.backdrop
        else repo.episodeStills(it.title, it.season)[it.num]?.also { still ->
            val m = store.epMeta(it.episodeUrl) ?: EpMeta(it.seriesUrl, it.title, it.season, it.num, it.subtitle, null)
            store.saveEpMeta(it.episodeUrl, m.copy(image = still)); bump()
        }
    } catch (_: Exception) { null }
    if (img != null) imageCache[it.key + it.episodeUrl] = img
    return img
}

fun AppViewModel.playContinue(it: ContinueItem) {
    val sUrl = it.seriesUrl
    if (sUrl == null) { play(it.play); return }
    detail = Channel(-1, it.title, sUrl, it.poster, it.group); info = null
    episodes = epListCache[sUrl].orEmpty(); season = it.season
    store.setContCursor(sUrl, it.episodeUrl)
    play(it.play, PlayMode.Vod)
    val sid = sUrl.removePrefix("series:").toIntOrNull()
    if (episodes.isEmpty() && sid != null) viewModelScope.launch {
        try {
            val l = repo.loadEpisodes(sid)
            epListCache[sUrl] = l
            if (detail?.url == sUrl) episodes = l
        } catch (e: Exception) { Diag.note("AppViewModel#2", e) }
    }
}

/** Moves a series card to the next (+1) or previous (-1) episode. Tapping the card then plays that episode. */
fun AppViewModel.continueStep(it: ContinueItem, delta: Int) {
    val sUrl = it.seriesUrl ?: return
    val sid = sUrl.removePrefix("series:").toIntOrNull() ?: return
    viewModelScope.launch {
        val eps = epListCache[sUrl] ?: try { repo.loadEpisodes(sid).also { epListCache[sUrl] = it } }
            catch (e: Exception) { say(friendly(e)); return@launch }
        val t = if (delta > 0) VmLogic.nextEpisode(eps, it.episodeUrl) else VmLogic.previousEpisode(eps, it.episodeUrl)
        if (t == null) { say(tx(if (delta > 0) R.string.x_cont_no_next else R.string.x_cont_no_prev)); return@launch }
        val still = try { repo.episodeStills(it.title, t.season)[t.num] } catch (_: Exception) { null }
        store.saveEpMeta(t.url, EpMeta(sUrl, it.title, t.season, t.num, t.title, still ?: VmLogic.episodeImage(t, eps, it.poster)))
        store.setContCursor(sUrl, t.url)
        bump()
    }
}

/**
 * An episode was just finished: make Continue watching offer the NEXT episode ("up next") with its own image.
 * Skipped when the user already moved on (autoplay / picked another episode) or it was the last episode.
 */
internal fun AppViewModel.advanceAfterFinish(c: Channel) {
    val m = store.epMeta(c.url) ?: return
    val cur = store.contCursor(m.seriesUrl)
    if (cur != null && cur.first != c.url) return
    val sid = m.seriesUrl.removePrefix("series:").toIntOrNull() ?: return
    viewModelScope.launch {
        val eps = epListCache[m.seriesUrl]
            ?: (if (detail?.url == m.seriesUrl && episodes.isNotEmpty()) episodes else null)
            ?: try { repo.loadEpisodes(sid) } catch (_: Exception) { return@launch }
        epListCache[m.seriesUrl] = eps
        val t = VmLogic.nextEpisode(eps, c.url) ?: return@launch
        val still = try { repo.episodeStills(m.seriesName, t.season)[t.num] } catch (_: Exception) { null }
        store.saveEpMeta(t.url, EpMeta(m.seriesUrl, m.seriesName, t.season, t.num, t.title, still ?: VmLogic.episodeImage(t, eps, c.logo)))
        store.setContCursor(m.seriesUrl, t.url)
        bump()
    }
}

fun AppViewModel.continueMenu(it: ContinueItem) {
    val items = ArrayList<Pair<String, () -> Unit>>()
    val title = Channel(-1, it.title, it.seriesUrl ?: it.episodeUrl, it.poster, it.group)
    items += tx(R.string.play) to { playContinue(it) }
    if (it.seriesUrl != null) {
        items += tx(R.string.x_cont_next) to { continueStep(it, 1) }
        items += tx(R.string.x_cont_prev) to { continueStep(it, -1) }
    }
    items += tx(R.string.details) to { openDetail(title) }
    items += (if (title.url in favs) tx(R.string.menu_fav_remove) else tx(R.string.menu_fav_add)) to { toggleFav(title) }
    items += tx(R.string.menu_remove_continue) to { removeFromContinue(it) }
    menu = MenuRequest(if (it.badge.isNotEmpty()) "${it.title} · ${it.badge}" else it.title, items)
}

/** Removes a movie / series from Continue watching (its watch progress is forgotten; favourites are untouched). */
fun AppViewModel.removeFromContinue(it: ContinueItem) {
    if (it.seriesUrl == null) {
        store.removeHistory(listOf(it.episodeUrl))
    } else {
        val urls = history.filter { h -> (store.epMeta(h.url) ?: legacyMeta(h))?.seriesUrl == it.seriesUrl }.map { h -> h.url }
        store.removeSeriesProgress(it.seriesUrl, urls)
    }
    WatchNext.remove(ctx, it.episodeUrl)
    history = store.history()
    bump(); say(tx(R.string.menu_removed))
}

/** Removes one movie / episode from the watch history (also takes it out of Continue watching). */
fun AppViewModel.removeFromHistory(c: Channel) {
    store.removeHistory(listOf(c.url))
    WatchNext.remove(ctx, c.url)
    history = store.history()
    bump(); say(tx(R.string.menu_removed))
}

/** Episode that follows the one currently playing, if any (for auto-next). */
fun AppViewModel.nextEpisodeOf(c: Channel): Episode? {
    return VmLogic.nextEpisode(episodes, c.url)
}

/** Episode before the current one, if any. */
fun AppViewModel.previousEpisodeOf(c: Channel): Episode? {
    return VmLogic.previousEpisode(episodes, c.url)
}

fun AppViewModel.hasNextEpisode(c: Channel): Boolean = nextEpisodeOf(c) != null

fun AppViewModel.hasPreviousEpisode(c: Channel): Boolean = previousEpisodeOf(c) != null

fun AppViewModel.playNext() {
    val c = current ?: return
    val d = detail ?: return
    nextEpisodeOf(c)?.let { playEpisode(d, it) }
}

fun AppViewModel.playPrevious() {
    val c = current ?: return
    val d = detail ?: return
    previousEpisodeOf(c)?.let { playEpisode(d, it) }
}

/** Next/previous item for the player's transport row: channels in live, episodes in a series. */
fun AppViewModel.playNeighbor(delta: Int) {
    if (playMode == PlayMode.Live) zap(delta)
    else if (delta > 0) playNext() else playPrevious()
}

fun AppViewModel.hasNeighbor(delta: Int): Boolean {
    if (playMode == PlayMode.Live) return zapEnabled
    val c = current ?: return false
    return if (delta > 0) hasNextEpisode(c) else hasPreviousEpisode(c)
}

fun AppViewModel.playCatchup(c: Channel, p: Prog) {
    val u = repo.catchupUrl(c, p)
    if (u == null) { say(tx(R.string.no_catchup)); return }
    play(Channel(-1, "${c.name} · ${p.title}", u, c.logo, c.group), PlayMode.Catchup, 0)
}

fun AppViewModel.playRecording(r: Recording) {
    val f = r.file ?: return
    play(Channel(-1, r.title, "file://$f", null, r.channelName), PlayMode.Recording, 0)
}

fun AppViewModel.stop() {
    sleepAt = 0L; history = store.history(); restoreFocus = true; screen = back
    // Re-deriving every list (tens of thousands of channels) on the main thread made the way back from the player
    // take seconds. History is observable state, so shelves refresh on their own; lists only depend on health when these are on.
    if (store.hideDead || store.mergeDuplicates) bump()
    refreshRecs()
}

fun AppViewModel.zap(delta: Int) {
    if (!zapEnabled) return
    val list = zapList
    val i = list.indexOfFirst { it.url == current?.url }
    var idx = if (i < 0) 0 else i
    repeat(list.size) {
        idx = (idx + delta + list.size) % list.size
        val c = list[idx]
        if (!parental.needsPin(c) && !health.isDead(c.url)) { tried = hashSetOf(c.url); current = c; return }
    }
}

/** Direct channel number entry from the remote's digits. */
fun AppViewModel.zapToNumber(n: Int) {
    val list = zapList
    val c = list.firstOrNull { it.number == n } ?: list.getOrNull(n - 1) ?: return
    if (parental.needsPin(c)) return
    tried = hashSetOf(c.url); current = c
}

/** Switch to a specific channel while the player is open (side panel / last channel). */
fun AppViewModel.switchTo(c: Channel) {
    if (parental.needsPin(c)) return
    tried = hashSetOf(c.url); current = c
}

/** Same channel from another source (HD/FHD/SD duplicates), used by manual switch and auto-failover. */
fun AppViewModel.alternativesOf(c: Channel): List<Channel> = health.alternatives(c, all)

fun AppViewModel.failover(): Boolean {
    val c = current ?: return false
    val next = alternativesOf(c).firstOrNull { it.url !in tried } ?: return false
    tried += next.url
    current = next
    return true
}

fun AppViewModel.useAlternative(c: Channel) { tried += c.url; current = c }

/**
 * A movie or episode failed to play: try the SAME title from another source (never a different title: see
 * [VmLogic.sameTitle]). Returns true when something is being tried; an episode lookup is asynchronous and [onNone]
 * runs if no source has that episode. Each failure comes back here, so sources are tried one after another.
 */
fun AppViewModel.tryVodFailover(onNone: () -> Unit): Boolean {
    val c = current ?: return false
    if (playMode != PlayMode.Vod) return false
    val meta = store.epMeta(c.url)
    if (meta == null) {
        if (kindOf(c) != Kind.Movie) return false
        val alt = movies.asSequence()
            .filter { it.url != c.url && it.url !in tried && health.state(it.url) != Health.Dead && VmLogic.sameTitle(it.name, c.name) }
            .sortedBy { health.score(it.url) }.firstOrNull() ?: return false
        tried += alt.url; current = alt
        return true
    }
    val cands = series.filter { it.url != meta.seriesUrl && VmLogic.sameTitle(it.name, meta.seriesName) }.take(4)
    if (cands.isEmpty()) return false
    viewModelScope.launch {
        var switched = false
        for (s in cands) {
            val sid = s.url.removePrefix("series:").toIntOrNull() ?: continue
            val eps = epListCache[s.url] ?: try { repo.loadEpisodes(sid) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("Failover", "episodes of ${s.name} unavailable", e); continue
            }
            epListCache[s.url] = eps
            val e = VmLogic.episodeIn(eps, meta.season, meta.num) ?: continue
            if (e.url in tried || health.state(e.url) == Health.Dead) continue
            if (current?.url != c.url) return@launch            // the user already left or changed episode
            tried += e.url
            // Continue watching keeps pointing at the ORIGINAL series, whichever source is playing.
            store.saveEpMeta(e.url, EpMeta(meta.seriesUrl, meta.seriesName, meta.season, meta.num, meta.title, meta.image))
            store.setContCursor(meta.seriesUrl, e.url)
            current = Channel(-1, c.name, e.url, c.logo, c.group)
            bump(); switched = true; break
        }
        if (!switched && current?.url == c.url) onNone()
    }
    return true
}

/** Optional: warm the next and previous channel of the zap list (see [Prefetch]). */
fun AppViewModel.prefetchNeighbors() {
    if (!store.prefetchNext || !zapEnabled) return
    val list = zapList
    val i = list.indexOfFirst { it.url == current?.url }
    if (i < 0 || list.size < 2) return
    val n = list.size
    val targets = listOf(list[(i + 1) % n], list[(i - 1 + n) % n])
        .filter { !parental.needsPin(it) && !health.isDead(it.url) }
    viewModelScope.launch { targets.forEach { Prefetch.warm(it.url) } }
}

/** Local report of the channels that failed most often, for the settings screen. */
fun AppViewModel.problemReport(): List<String> = store.worstHealth(15).map { (u, r) ->
    val name = all.firstOrNull { it.url == u }?.name ?: u.takeLast(30)
    tx(R.string.x_problem_line, name, tx(if (r.state == Health.Dead) R.string.x_word_dead else R.string.x_word_slow), r.failures)
}

fun AppViewModel.noteStart(c: Channel) {
    val old = store.historyOf(c.url)
    store.recordWatch(c, kindOf(c), old?.position ?: 0, old?.duration ?: 0, true)
}

fun AppViewModel.saveProgress(c: Channel, pos: Long, dur: Long) {
    if (kindOf(c) == Kind.Live || playMode == PlayMode.Catchup) return
    val wasFinished = store.historyOf(c.url)?.finished == true
    store.recordWatch(c, kindOf(c), pos, dur, false)
    if (!wasFinished && store.historyOf(c.url)?.finished == true) { traktMarkWatched(c); advanceAfterFinish(c); dropLater(c.url) }
    // The last save happens while the player is being torn down (after stop()), so Continue watching must refresh here.
    if (screen != Screen.Player) history = store.history()
    // Launcher/widget updates touch content providers: keep them off the main thread so leaving the player stays quick.
    val entry = if (parental.visible(c)) store.historyOf(c.url) else null
    viewModelScope.launch(Dispatchers.IO) {
        entry?.let { WatchNext.sync(ctx, it) }   // Android TV "Watch Next" row
        NabdWidget.refresh(ctx)                  // phone home-screen widget
    }
}

fun AppViewModel.restoreAfterKill(tabName: String?, detailFields: Array<String>?) {
    if (all.isNotEmpty()) return   // the ViewModel survived (rotation etc.): nothing was lost
    pendingRestore = {
        runCatching { tabName?.let { tab = Tab.valueOf(it) } }
        if (detailFields != null && detailFields.size >= 4 && detailFields[1].isNotBlank())
            open(Channel(0, detailFields[0], detailFields[1], detailFields[2].ifBlank { null }, detailFields[3]))
    }
}

fun AppViewModel.resumeFromWatchNext(url: String) {
    if (all.isEmpty()) { pendingResume = url; return }
    playResume(url)
}

internal fun AppViewModel.playResume(token: String) {
    // token is either an opaque WatchNext key or (older tiles) the raw url
    val h = store.historyOf(token) ?: store.history().firstOrNull { WatchNext.keyOf(it.url) == token } ?: return
    play(histChannel(h))
}

fun AppViewModel.clearHistory() { store.clearHistory(); WatchNext.clearAll(ctx); history = emptyList(); recs = emptyList(); because = null; runCatching { recsFile.delete() }; bump() }

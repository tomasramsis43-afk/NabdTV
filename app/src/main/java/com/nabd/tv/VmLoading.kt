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
internal fun AppViewModel.friendly(e: Exception): String = when (e) {
    is java.net.UnknownHostException -> tx(R.string.x_err_unknown_host)
    is java.net.SocketTimeoutException -> tx(R.string.x_err_timeout)
    is java.net.ConnectException -> tx(R.string.x_err_refused)
    is IllegalArgumentException -> tx(R.string.x_err_bad_address, e.message ?: "")
    is org.json.JSONException -> tx(R.string.x_err_json)
    else -> e.message ?: tx(R.string.x_err_unknown)
}

internal fun AppViewModel.recordMsg(scheduled: Boolean, clash: Boolean): String = when {
    !scheduled -> tx(R.string.x_rec_already)
    clash -> tx(R.string.x_rec_clash)
    else -> tx(R.string.x_rec_ok)
}

/**
 * Stale-while-revalidate: the cached lists are on screen at once. If they are older than 12 h, refresh quietly in the
 * background (after startup has settled) and swap the new data in; offline or on a server error the cache simply stays.
 */
internal fun AppViewModel.refreshInBackgroundIfStale() {
    if (!store.autoRefresh || (repo.playlistUrl.isBlank() && !hasXtream)) return
    viewModelScope.launch {
        kotlinx.coroutines.delay(90_000L)
        if (System.currentTimeMillis() - store.lastSyncAt < 12 * 3_600_000L) return@launch
        try {
            val fresh = repo.load(true)
            if (fresh.isNotEmpty()) all = fresh
            if (hasXtream) { fetchVod(Tab.Movies, true, silent = true); fetchVod(Tab.Series, true, silent = true) }
            store.lastSyncAt = System.currentTimeMillis()
            bump()
        } catch (e: Exception) { Diag.note("background refresh", e) }
    }
}

fun AppViewModel.load(force: Boolean) {
    viewModelScope.launch {
        loading = true; error = null
        try {
            all = repo.load(force)
            if (force) store.lastSyncAt = System.currentTimeMillis()
            group = GROUP_ALL
            warmLists()
            screen = Screen.Main
            preloadVod()
            pendingRestore?.let { pendingRestore = null; it() }
            pendingResume?.let { pendingResume = null; playResume(it) }
            launch {
                epg = repo.loadEpg(all, force)
                Recorder.syncAuto(ctx, store, all, epg)
                recCtl.refresh()
                bump()
            }
            refreshRecs()
        } catch (e: Exception) {
            error = friendly(e)
            if (all.isEmpty()) screen = Screen.Setup
        }
        loading = false
    }
}

internal suspend fun AppViewModel.fetchVod(t: Tab, force: Boolean, silent: Boolean) {
    if (!vodActive.add(t)) return
    vodBusy++; vodLoading = true
    if (!silent) error = null
    try {
        if (t == Tab.Movies) movies = repo.loadMovies(force) else series = repo.loadSeries(force)
        warmLists()
        refreshRecs()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!silent) error = friendly(e)
    } catch (e: OutOfMemoryError) {
        if (!silent) error = tx(R.string.err_oom)
    } finally {
        vodActive.remove(t)
        vodBusy--; vodLoading = vodBusy > 0
    }
}

internal fun AppViewModel.loadVod(t: Tab, force: Boolean) {
    viewModelScope.launch { clearJob?.join(); fetchVod(t, force, silent = false) }
}

/** Movies and series load automatically in the background right after the live list, so Home shelves are filled without visiting those tabs. */
internal fun AppViewModel.preloadVod() {
    if (!hasXtream) return
    viewModelScope.launch {
        clearJob?.join()
        if (movies.isEmpty()) fetchVod(Tab.Movies, false, silent = true)
        if (series.isEmpty()) fetchVod(Tab.Series, false, silent = true)
    }
}

fun AppViewModel.selectTab(t: Tab) {
    tab = t; group = GROUP_ALL; query = ""
    // A fresh start on the first card of the page (scroll + focus), unlike returning from a movie page.
    lastFocusKey = null; restoreFocus = false
    gridStates.keys.removeAll { it.startsWith(t.name + "|") || it == "search" }
    if (t == Tab.Home) listStates.keys.removeAll { it == "home" || it.startsWith("row:") }
    focusContentTick++
    if (t == Tab.Movies && movies.isEmpty() && hasXtream) loadVod(t, false)
    if (t == Tab.Series && series.isEmpty() && hasXtream) loadVod(t, false)
    if (t == Tab.Guide && guideChannel == null) guideChannel = guideChannels.firstOrNull()
    if (t == Tab.Recordings) recCtl.refresh()
}

fun AppViewModel.refresh() {
    if (tab == Tab.Movies || tab == Tab.Series) loadVod(tab, true)
    else if (tab == Tab.Home && hasXtream) refreshAccount()
    else load(true)
}

/** Re-reads everything from the provider: account status, channels, movies, series and the guide. */
fun AppViewModel.refreshAccount() {
    viewModelScope.launch {
        say(tx(R.string.msg_refreshing))
        val info = repo.accountInfo()
        if (info != null) say(info)
        load(true)
        if (hasXtream) {
            clearJob?.join()
            fetchVod(Tab.Movies, true, silent = false)
            fetchVod(Tab.Series, true, silent = false)
        }
    }
}

internal fun AppViewModel.resetVod(dropCache: Boolean = true) {
    movies = emptyList(); series = emptyList(); tab = Tab.Home
    if (dropCache) clearJob = viewModelScope.launch { repo.clearVod() }
}

/** [cached] = switching to a saved provider: its own cached lists are shown at once (and refreshed later in the background). */
fun AppViewModel.setUrl(url: String, cached: Boolean = false) {
    repo.epgManual = false; repo.xtream = ""; repo.playlistUrl = url.trim()
    repo.rememberSource("m3u", url.trim())
    enterSourceData()
    resetVod(!cached); load(!cached)
}

/** Xtream Codes login → standard M3U + XMLTV endpoints. */
fun AppViewModel.setXtream(host: String, user: String, pass: String, cached: Boolean = false) {
    var b = host.trim().trimEnd('/')
    if (!b.startsWith("http")) b = "http://$b"
    val q = "username=${Uri.encode(user.trim())}&password=${Uri.encode(pass.trim())}"
    repo.epgManual = true
    repo.xtream = "$b\n${user.trim()}\n${pass.trim()}"
    repo.epgUrl = "$b/xmltv.php?$q"
    repo.playlistUrl = "$b/get.php?$q&type=m3u_plus&output=ts"
    repo.rememberSource("xtream", b, user.trim(), pass.trim())
    enterSourceData()
    resetVod(!cached); load(!cached)
}

/** Each provider has its own favourites, history and settings: opens the data of the provider now in use and refreshes the screens. */
internal fun AppViewModel.enterSourceData() {
    val id = repo.currentSourceId() ?: return
    if (store.useSource(id)) reloadProfileState()
}

fun AppViewModel.editSource(s: SavedSource, name: String, icon: String, color: Int) = repo.editSource(s.id, name, icon, color)

fun AppViewModel.savedSources(): List<SavedSource> { repo.ensureCurrentSaved(); return repo.savedSources() }

fun AppViewModel.currentSourceId(): String? = repo.currentSourceId()

fun AppViewModel.forgetSource(s: SavedSource) { repo.forgetSource(s.id) }

/** Switches to a saved provider: its cached lists show at once; a provider never opened before is downloaded. */
fun AppViewModel.switchSource(s: SavedSource) {
    if (s.id == repo.currentSourceId()) return
    if (s.kind == "xtream") setXtream(s.address, s.user, s.pass, cached = true) else setUrl(s.address, cached = true)
}

fun AppViewModel.openSetup() { back = Screen.Main; screen = Screen.Setup }

fun AppViewModel.closeSetup() { if (all.isNotEmpty()) screen = Screen.Main }

fun AppViewModel.openSettings() { if (profile.kids) requirePin(tx(R.string.pin_enter)) { screen = Screen.Settings } else screen = Screen.Settings }

fun AppViewModel.closeSettings() { restoreFocus = true; screen = Screen.Main; bump() }

fun AppViewModel.runDiag() {
    viewModelScope.launch {
        diagBusy = true; diag = null
        diag = try { repo.diagnose() } catch (e: Exception) { tx(R.string.diag_failed, e.message ?: "") }
        diagBusy = false
    }
}

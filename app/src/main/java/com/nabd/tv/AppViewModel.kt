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

enum class Screen { Setup, Profiles, Main, Player, Detail, MultiView, Settings }
enum class Tab(@androidx.annotation.StringRes val labelRes: Int) {
    Home(R.string.tab_home), Live(R.string.tab_live), Movies(R.string.tab_movies), Series(R.string.tab_series), Guide(R.string.tab_guide), Recordings(R.string.tab_recordings)
}
enum class PlayMode { Live, Vod, Catchup, Recording }

const val GROUP_ALL = "الكل"
const val GROUP_FAV = "★ المفضلة"
const val GROUP_RECENT = "◷ شاهدت مؤخراً"
const val GROUP_NEW = "🆕 المضاف حديثاً"
const val GROUP_CONT = "▶ متابعة المشاهدة"
private const val NEW_LIMIT = 300
const val GROUP_CUSTOM = "◆ "

class PinRequest(val title: String, val onOk: () -> Unit)
class MenuRequest(val title: String, val items: List<Pair<String, () -> Unit>>)
class PromptRequest(val title: String, val initial: String, val secret: Boolean = false, val onOk: (String) -> Unit)

fun kindOf(c: Channel): Kind = when {
    c.url.startsWith("series:") || c.url.contains("/series/") -> Kind.Series
    c.url.contains("/movie/") -> Kind.Movie
    else -> Kind.Live
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application = app

    /** Localised string for non-composable code. */
    fun tx(@androidx.annotation.StringRes id: Int, vararg a: Any): String = Lang.str(ctx, id, *a)
    val store = Store(app)
    init { Logos.enabled = store.titleLogos }
    val repo = Repo(app, store)
    val parental = Parental(store)
    val health = HealthChecker(repo, store)

    /* ───────── navigation ───────── */
    private var screenState by mutableStateOf(when {
        repo.playlistUrl.isBlank() -> Screen.Setup
        store.profiles().size > 1 -> Screen.Profiles
        else -> Screen.Main
    })
    var screen: Screen
        get() = screenState
        private set(v) { screenState = v; syncTheme() }

    /** User's light/dark choice. Video screens are always dark so controls and overlays stay readable. */
    var darkPref by mutableStateOf(store.darkMode); private set
    private fun syncTheme() { Ui.dark = darkPref || screenState == Screen.Player || screenState == Screen.MultiView || screenState == Screen.Detail }
    fun setDark(on: Boolean) { store.darkMode = on; darkPref = on; syncTheme() }

    /* Remembered UI position, so coming back from the player / details lands exactly where you were. */
    @JvmField var restoreFocus = false
    @JvmField var lastFocusKey: String? = null
    /** Focus requesters of the cards currently on screen, by card key; and the key of the first card of the current page. */
    val cardFocus = HashMap<String, androidx.compose.ui.focus.FocusRequester>()
    @JvmField var firstKey: String? = null
    var focusContentTick by mutableIntStateOf(0)
    /** Where the remote should land when entering the page: the card you left, else the first card. */
    fun contentEntry(): androidx.compose.ui.focus.FocusRequester? =
        lastFocusKey?.let { cardFocus[it] } ?: firstKey?.let { cardFocus[it] }
    fun enterContent(): Boolean = contentEntry()?.let { runCatching { it.requestFocus(); true }.getOrDefault(false) } ?: false
    private val gridStates = HashMap<String, androidx.compose.foundation.lazy.grid.LazyGridState>()
    private val listStates = HashMap<String, androidx.compose.foundation.lazy.LazyListState>()
    fun gridState(key: String) = gridStates.getOrPut(key) { androidx.compose.foundation.lazy.grid.LazyGridState() }
    fun listState(key: String) = listStates.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState() }
    var tab by mutableStateOf(runCatching { Tab.valueOf(store.startSection) }.getOrDefault(Tab.Home))
        private set
    private var back = Screen.Main

    /** Bumped whenever something that affects visibility changes (unlock, hide, health…). */
    var rev by mutableIntStateOf(0)
        private set
    private fun bump() { rev++ }

    /** Re-derives the lists after a setting that changes what they show. */
    fun listsChanged() = bump()

    var inPip by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var pin by mutableStateOf<PinRequest?>(null)
    var menu by mutableStateOf<MenuRequest?>(null)
    var prompt by mutableStateOf<PromptRequest?>(null)

    /* ───────── content ───────── */
    var all by mutableStateOf<List<Channel>>(emptyList()); private set
    var movies by mutableStateOf<List<Channel>>(emptyList()); private set
    var series by mutableStateOf<List<Channel>>(emptyList()); private set
    var epg by mutableStateOf<Map<String, List<Prog>>>(emptyMap()); private set
    var loading by mutableStateOf(false); private set
    var vodLoading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    var favs by mutableStateOf(store.favorites); private set
    var history by mutableStateOf(store.history()); private set
    /** url → history entry, rebuilt only when history changes (cards look progress up here instead of scanning the list). */
    val historyByUrl: Map<String, HistoryEntry> by derivedStateOf { history.associateBy { it.url } }
    private val recCtl = RecordingsController(app, store)
    val recordings: List<Recording> get() = recCtl.items
    var recs by mutableStateOf<List<Channel>>(emptyList()); private set
    var because by mutableStateOf<Pair<String, List<Channel>>?>(null); private set

    var group by mutableStateOf(GROUP_ALL)
    var query by mutableStateOf("")

    val savedUrl: String get() = repo.playlistUrl
    val hasXtream: Boolean get() = repo.hasXtream

    /** The start-up splash is shown once per process; it ends when [startupReady] holds (or after its time cap). */
    var splashDone by mutableStateOf(false)

    /** Observable copy of the "hide names under posters" option, so every poster updates the moment it is switched. */
    var hidePosterTitles by mutableStateOf(store.hidePosterTitles); private set
    fun applyHidePosterTitles(v: Boolean) { hidePosterTitles = v; store.hidePosterTitles = v }

    /** The first screen has something real to show: cached lists loaded, or an error / the setup screen is needed. */
    fun startupReady(): Boolean = screen == Screen.Setup || error != null || (!loading && all.isNotEmpty() && !vodLoading)

    private fun ok(c: Channel, live: Boolean, hidden: Set<String>): Boolean =
        parental.visible(c) && c.url !in hidden && !(live && store.hideDead && health.isDead(c.url))

    val liveList by derivedStateOf {
        rev
        val h = store.hiddenChannels
        val l = all.filter { ok(it, true, h) }
        if (store.mergeDuplicates) dedupeSources(l) else l
    }

    /** Keeps one entry per channel name (quality tags ignored), preferring the healthiest source. Others stay available as alternatives. */
    private fun dedupeSources(l: List<Channel>): List<Channel> {
        fun rank(c: Channel) = when (health.state(c.url)) { Health.Ok -> 0; Health.Unknown -> 1; Health.Slow -> 2; Health.Dead -> 3 }
        return VmLogic.dedupeSources(l, ::rank)
    }
    val movieList by derivedStateOf { rev; val h = store.hiddenChannels; movies.filter { ok(it, false, h) } }
    val seriesList by derivedStateOf { rev; val h = store.hiddenChannels; series.filter { ok(it, false, h) } }

    private val base: List<Channel>
        get() = when (tab) { Tab.Movies -> movieList; Tab.Series -> seriesList; else -> liveList }

    // Booleans only: the category list is not rebuilt every time the continue-watching list changes.
    private val hasContMovies by derivedStateOf { continueItems.any { it.kind == Kind.Movie } }
    private val hasContSeries by derivedStateOf { continueItems.any { it.kind == Kind.Series } }

    val groups by derivedStateOf {
        rev
        val pinned = store.pinnedGroups
        val order = repo.groupOrder(when (tab) { Tab.Movies -> "movie"; Tab.Series -> "series"; else -> "live" })
        val pos = HashMap<String, Int>().also { m -> order.forEachIndexed { i, n -> m[n] = i } }
        val seen = base.map { it.group }.distinct()
        // Provider order; before the first refresh (no saved order yet) use the order the list arrived in.
        val names = if (order.isEmpty()) seen else seen.sortedWith(compareBy({ pos[it] ?: Int.MAX_VALUE }, { it }))
        val ordered = names.filter { it in pinned } + names.filter { it !in pinned }
        val special = mutableListOf(GROUP_ALL, GROUP_FAV)
        if (base.any { it.added > 0 }) special += GROUP_NEW
        // Continue watching lives in its own category under "recently added" (movies in Movies, episodes in Series).
        if ((tab == Tab.Movies && hasContMovies) || (tab == Tab.Series && hasContSeries)) special += GROUP_CONT
        if (tab == Tab.Live) {
            special += GROUP_RECENT
            special += store.customGroups().keys.map { GROUP_CUSTOM + it }
        }
        special + ordered
    }

    fun groupLabel(g: String): String =
        if (g == GROUP_ALL) tx(R.string.group_all) else if (g == GROUP_FAV) tx(R.string.group_fav)
        else if (g == GROUP_RECENT) tx(R.string.group_recent) else if (g == GROUP_NEW) tx(R.string.group_new)
        else if (g == GROUP_CONT) tx(R.string.group_continue)
        else if (g.startsWith("★") || g.startsWith("◷") || g.startsWith("◆") || g.startsWith("🆕")) g
        else (if (g in store.pinnedGroups) "📌 " else "") + Classifier.cleanGroup(g, store.filterList) +
            (if (g in store.lockedGroups) " 🔒" else "")

    val visible by derivedStateOf {
        rev
        val custom = if (group.startsWith(GROUP_CUSTOM)) store.customGroups()[group.removePrefix(GROUP_CUSTOM)]?.toSet() ?: emptySet() else emptySet()
        val recent = if (group == GROUP_RECENT) history.filter { it.kind == Kind.Live }.map { it.url }.toSet() else emptySet()
        val src = if (group == GROUP_NEW) base.filter { it.added > 0 }.sortedByDescending { it.added }.take(NEW_LIMIT) else base
        src.filter {
            val g = when {
                group == GROUP_ALL || group == GROUP_NEW -> true
                group == GROUP_FAV -> it.url in favs
                group == GROUP_RECENT -> it.url in recent
                group.startsWith(GROUP_CUSTOM) -> it.url in custom
                else -> it.group == group
            }
            g && (query.isBlank() || it.name.contains(query, ignoreCase = true))
        }
    }

    /** Global search across live, movies and series, answered by the database. */
    var searchResults by mutableStateOf<List<Channel>>(emptyList()); private set
    /** Programs (now or upcoming) whose title matches the search, taken from the EPG. */
    var progResults by mutableStateOf<List<Pair<Channel, Prog>>>(emptyList()); private set
    private var searchJob: kotlinx.coroutines.Job? = null

    fun runSearch() {
        searchJob?.cancel()
        val q = query
        if (q.isBlank()) { searchResults = emptyList(); progResults = emptyList(); return }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250)   // debounce typing
            val h = store.hiddenChannels
            val rows = withContext(Dispatchers.IO) { repo.dao.search(q) }
            searchResults = rows.map { it.toChannel() }.filter { ok(it, kindOf(it) == Kind.Live, h) }
            val now = System.currentTimeMillis()
            val epgNow = epg
            val live = liveList
            progResults = if (q.trim().length < 2 || epgNow.isEmpty()) emptyList() else withContext(Dispatchers.Default) {
                val byUrl = live.associateBy { it.url }
                epgNow.entries.flatMap { (url, list) ->
                    val c = byUrl[url] ?: return@flatMap emptyList<Pair<Channel, Prog>>()
                    list.filter { it.stop > now && it.title.contains(q, ignoreCase = true) }.take(2).map { c to it }
                }.sortedBy { it.second.start }.take(40)
            }
        }
    }

    /* ───────── home shelves ───────── */
    private fun histChannel(h: HistoryEntry) = Channel(-1, h.name, h.url, h.logo, h.group)

    /** TMDB daily trending + top-rated charts (null until loaded / without a TMDB key). */
    var tmdbCharts by mutableStateOf<TmdbCharts.Charts?>(null); private set
    private var chartsAt = 0L
    // Title indexes used to match the TMDB charts. Built ONCE per loaded list on a background thread. (They used to be rebuilt
    // on the UI thread after every state bump, normalising every title of a 50k-item library each time: it froze TV boxes.)
    private var movieTitleIdx by mutableStateOf<Map<String, List<Pair<Channel, Int?>>>>(emptyMap())
    private var seriesTitleIdx by mutableStateOf<Map<String, List<Pair<Channel, Int?>>>>(emptyMap())

    /** Loads the TMDB charts (cached 6 h). Call again with [force] after the key changes. */
    fun refreshTmdbCharts(force: Boolean = false) {
        val key = store.tmdbKey
        if (key.isBlank()) { tmdbCharts = null; return }
        if (!force && tmdbCharts != null && System.currentTimeMillis() - chartsAt < 6 * 3_600_000L) return
        viewModelScope.launch {
            val c = withContext(Dispatchers.IO) { runCatching { TmdbCharts.load(key) }.getOrNull() }
            if (c != null) { tmdbCharts = c; chartsAt = System.currentTimeMillis(); TmdbCharts.save(ctx, c, chartsAt) }
            else if (force) tmdbCharts = null
        }
    }

    val shelves by derivedStateOf {
        rev
        val out = ArrayList<Shelf>()
        // "Continue watching" is its own landscape row (see continueItems / ContinueUi.kt), no longer a poster shelf.
        // Favourites: only what the user actually starred (any kind), already filtered by parental rules.
        val favItems = all.filter { it.url in favs && parental.visible(it) }.take(40)
        // Daily trending rows (movies + series) are ALWAYS shown. Source: TMDB's own charts when the TMDB key is set and enough of
        // those titles exist in the library (wide TMDB artwork + TMDB rating); otherwise the provider's own data.
        val ch = tmdbCharts
        val hid = store.hiddenChannels
        val ok = { c: Channel -> parental.visible(c) && c.url !in hid }
        fun row(
            title: String, idx: Map<String, List<Pair<Channel, Int?>>>, entries: List<TmdbCharts.Entry>?,
            fallback: () -> List<Channel>
        ) {
            val (items, art) = if (entries != null) TmdbCharts.pick(idx, entries, 10, ok) else (emptyList<Channel>() to emptyMap())
            if (items.size >= 3) { out += Shelf(title, items, ranked = true, art = art); return }
            val f = fallback()
            if (f.isNotEmpty()) out += Shelf(title, f, ranked = true)
        }
        row(tx(R.string.x_shelf_trending_movies), movieTitleIdx, ch?.trendMovies) { VmLogic.trending(movieList.filter(ok), 10) }
        row(tx(R.string.x_shelf_trending_series), seriesTitleIdx, ch?.trendSeries) { VmLogic.trending(seriesList.filter(ok), 10) }
        val newMovies = movieList.filter { it.added > 0 }.sortedByDescending { it.added }.take(30).ifEmpty { movieList.take(30) }
        if (newMovies.isNotEmpty()) out += Shelf(tx(R.string.shelf_new_movies), newMovies)
        val newSeries = seriesList.filter { it.added > 0 }.sortedByDescending { it.added }.take(30).ifEmpty { seriesList.take(30) }
        if (newSeries.isNotEmpty()) out += Shelf(tx(R.string.shelf_new_series), newSeries)
        // Order: daily trending (movies, series) → new movies → new series → favourites → recently watched.
        if (laterUrls.isNotEmpty()) {
            val want = laterUrls.toHashSet(); val found = HashMap<String, Channel>()
            movieList.forEach { if (it.url in want) found[it.url] = it }
            seriesList.forEach { if (it.url in want) found[it.url] = it }
            val laterItems = laterUrls.mapNotNull { found[it] }
            if (laterItems.isNotEmpty()) out += Shelf(tx(R.string.x_shelf_later), laterItems)
        }
        if (favItems.isNotEmpty()) out += Shelf(tx(R.string.x_shelf_fav), favItems)
        val doneItems = history.filter { it.kind != Kind.Live && it.finished && parental.visible(histChannel(it)) }.take(20).map { histChannel(it) }
        if (doneItems.isNotEmpty()) out += Shelf(tx(R.string.x_shelf_recent), doneItems)
        out
    }

    /** Item currently focused on Home; drives the big preview on TV/tablet. */
    var homeFocus by mutableStateOf<Channel?>(null)
    /** TV home shows one row at a time: index of the visible row (continue-watching first, then the shelves). Survives opening a title. */
    var homeRow by mutableIntStateOf(0)
    var homeInfo by mutableStateOf<VodInfo?>(null); private set
    var homeInfoUrl: String? = null; private set
    suspend fun loadHomeInfo(c: Channel) {
        if (homeInfoUrl == c.url) return
        homeInfoUrl = c.url; homeInfo = null
        val k = kindOf(c)
        if (k != Kind.Live) homeInfo = runCatching { repo.vodInfo(c, k) }.getOrNull()
    }

    /** Poster + backdrop for [c] from caches / TMDB only (never hits the provider's API), for the adaptive image system. */
    suspend fun artworkFor(c: Channel, kind: Kind): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val info = repo.cachedInfo(c, kind)
        val year = info?.year?.toIntOrNull()
        val backdrop = info?.backdrop ?: runCatching { Artwork.backdrop(c.name, kind, year)?.url }.getOrNull()
        val poster = info?.poster ?: runCatching { Artwork.poster(c.name, kind, year)?.url }.getOrNull()
        poster to backdrop
    }

    private fun refreshRecs() {
        viewModelScope.launch {
            val h = history
            val f = favs
            val pool = liveList + movieList + seriesList
            val (r, b) = withContext(Dispatchers.Default) {
                Recommender.recommend(pool, h, f, 24) to Recommender.becauseYouWatched(pool, h, 20)
            }
            recs = r; because = b
        }
    }

    /* ───────── guide ───────── */
    fun nowNext(c: Channel): Pair<Prog?, Prog?> {
        return VmLogic.nowNext(epg[c.url], System.currentTimeMillis())
    }

    var guideChannel by mutableStateOf<Channel?>(null)
    val guideChannels by derivedStateOf { rev; liveList.filter { epg.containsKey(it.url) }.take(600) }

    /* ───────── loading ───────── */
    init {
        syncTheme()
        RefreshWorker.schedule(app)
        if (repo.playlistUrl.isNotBlank()) { load(false); refreshRecs() }
        checkUpdate(false)
        if (store.tmdbKey.isNotBlank()) TmdbCharts.loadCached(ctx)?.let { (c, at) -> tmdbCharts = c; chartsAt = at }   // instant rows, refreshed below when older than 6 h
        refreshTmdbCharts()
        viewModelScope.launch { androidx.compose.runtime.snapshotFlow { movies }.collect { l -> movieTitleIdx = withContext(Dispatchers.Default) { TmdbCharts.indexOf(l) } } }
        viewModelScope.launch { androidx.compose.runtime.snapshotFlow { series }.collect { l -> seriesTitleIdx = withContext(Dispatchers.Default) { TmdbCharts.indexOf(l) } } }
        refreshInBackgroundIfStale()
        viewModelScope.launch { while (true) { kotlinx.coroutines.delay(20 * 60_000L); checkUpdate(false) } }
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> tx(R.string.x_err_unknown_host)
        is java.net.SocketTimeoutException -> tx(R.string.x_err_timeout)
        is java.net.ConnectException -> tx(R.string.x_err_refused)
        is IllegalArgumentException -> tx(R.string.x_err_bad_address, e.message ?: "")
        is org.json.JSONException -> tx(R.string.x_err_json)
        else -> e.message ?: tx(R.string.x_err_unknown)
    }

    private fun recordMsg(scheduled: Boolean, clash: Boolean): String = when {
        !scheduled -> tx(R.string.x_rec_already)
        clash -> tx(R.string.x_rec_clash)
        else -> tx(R.string.x_rec_ok)
    }

    /**
     * Stale-while-revalidate: the cached lists are on screen at once. If they are older than 12 h, refresh quietly in the
     * background (after startup has settled) and swap the new data in; offline or on a server error the cache simply stays.
     */
    private fun refreshInBackgroundIfStale() {
        if (!store.autoRefresh || repo.playlistUrl.isBlank()) return
        viewModelScope.launch {
            kotlinx.coroutines.delay(90_000L)
            if (System.currentTimeMillis() - store.lastSyncAt < 12 * 3_600_000L) return@launch
            try {
                val fresh = repo.load(true)
                if (fresh.isNotEmpty()) all = fresh
                if (hasXtream) { fetchVod(Tab.Movies, true, silent = true); fetchVod(Tab.Series, true, silent = true) }
                store.lastSyncAt = System.currentTimeMillis()
                bump()
            } catch (_: Exception) { }
        }
    }

    fun load(force: Boolean) {
        viewModelScope.launch {
            loading = true; error = null
            try {
                all = repo.load(force)
                if (force) store.lastSyncAt = System.currentTimeMillis()
                group = GROUP_ALL
                screen = Screen.Main
                preloadVod()
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

    /** Tabs whose VOD list is being fetched right now (prevents duplicate / parallel loads of the same list). */
    private val vodActive = HashSet<Tab>()
    private var vodBusy = 0
    private var clearJob: kotlinx.coroutines.Job? = null

    private suspend fun fetchVod(t: Tab, force: Boolean, silent: Boolean) {
        if (!vodActive.add(t)) return
        vodBusy++; vodLoading = true
        if (!silent) error = null
        try {
            if (t == Tab.Movies) movies = repo.loadMovies(force) else series = repo.loadSeries(force)
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

    private fun loadVod(t: Tab, force: Boolean) {
        viewModelScope.launch { clearJob?.join(); fetchVod(t, force, silent = false) }
    }

    /** Movies and series load automatically in the background right after the live list, so Home shelves are filled without visiting those tabs. */
    private fun preloadVod() {
        if (!hasXtream) return
        viewModelScope.launch {
            clearJob?.join()
            if (movies.isEmpty()) fetchVod(Tab.Movies, false, silent = true)
            if (series.isEmpty()) fetchVod(Tab.Series, false, silent = true)
        }
    }

    fun selectTab(t: Tab) {
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

    fun refresh() {
        if (tab == Tab.Movies || tab == Tab.Series) loadVod(tab, true)
        else if (tab == Tab.Home && hasXtream) refreshAccount()
        else load(true)
    }

    /** Re-reads everything from the provider: account status, channels, movies, series and the guide. */
    fun refreshAccount() {
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

    private fun resetVod() {
        movies = emptyList(); series = emptyList(); tab = Tab.Home
        clearJob = viewModelScope.launch { repo.clearVod() }
    }

    fun setUrl(url: String) {
        repo.epgManual = false; repo.xtream = ""; repo.playlistUrl = url.trim()
        resetVod(); load(true)
    }

    /** Xtream Codes login → standard M3U + XMLTV endpoints. */
    fun setXtream(host: String, user: String, pass: String) {
        var b = host.trim().trimEnd('/')
        if (!b.startsWith("http")) b = "http://$b"
        val q = "username=${Uri.encode(user.trim())}&password=${Uri.encode(pass.trim())}"
        repo.epgManual = true
        repo.xtream = "$b\n${user.trim()}\n${pass.trim()}"
        repo.epgUrl = "$b/xmltv.php?$q"
        repo.playlistUrl = "$b/get.php?$q&type=m3u_plus&output=ts"
        resetVod(); load(true)
    }

    fun openSetup() { back = Screen.Main; screen = Screen.Setup }
    fun closeSetup() { if (all.isNotEmpty()) screen = Screen.Main }
    fun openSettings() { if (profile.kids) requirePin(tx(R.string.pin_enter)) { screen = Screen.Settings } else screen = Screen.Settings }
    fun closeSettings() { restoreFocus = true; screen = Screen.Main; bump() }

    var diag by mutableStateOf<String?>(null); private set
    var diagBusy by mutableStateOf(false); private set
    fun runDiag() {
        viewModelScope.launch {
            diagBusy = true; diag = null
            diag = try { repo.diagnose() } catch (e: Exception) { tx(R.string.diag_failed, e.message ?: "") }
            diagBusy = false
        }
    }

    /* ───────── sleep timer / daily limit / suggestions ───────── */
    var sleepAt by mutableStateOf(0L)

    fun limitReached(): Boolean {
        return VmLogic.limitReached(store.limitMinutes, store.hasPin, parental.unlocked, store.watchedSeconds().toLong())
    }

    /** Called every 30 s while something is playing. */
    fun tickWatch(sec: Int) {
        store.addWatched(sec)
        if (limitReached() && screen == Screen.Player) {
            stop()
            pin = PinRequest(tx(R.string.pin_time_limit)) { }
        }
    }

    /** "What should I watch?" — a few picks that fit the time of day and the user's taste. */
    fun tonight() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val pool = when {
            hour in 5..11 -> liveList.filter { Classifier.genre(it) == Genre.News }.ifEmpty { liveList }
            hour in 18..23 || hour < 2 -> (movieList + seriesList).ifEmpty { liveList }
            else -> liveList
        }
        val picks = (recs.shuffled().take(3) + pool.shuffled().take(5)).distinctBy { it.url }.take(6)
        if (picks.isEmpty()) { say(tx(R.string.no_suggestions)); return }
        menu = MenuRequest(tx(R.string.what_to_watch), picks.map { c -> (if (kindOf(c) == Kind.Live) "📺 " else "🎬 ") + c.name to { open(c) } })
    }

    /* ───────── in-app update ───────── */
    var update by mutableStateOf<Updater.Release?>(null); private set
    var updateProgress by mutableStateOf<Int?>(null); private set
    private var pendingApk: java.io.File? = null

    fun checkUpdate(manual: Boolean) {
        viewModelScope.launch {
            try {
                update = Updater.check(ctx, repo.http)
                if (!manual && update != null && UpdatePrefs.auto(ctx) && screen != Screen.Player && !PlaybackBus.active) installUpdate(auto = true)
                if (manual) say(if (update == null) tx(R.string.up_to_date) else tx(R.string.new_version, update!!.tag))
            } catch (e: Exception) {
                if (manual) say(tx(R.string.update_check_failed, e.message ?: ""))
            }
        }
    }

    fun installUpdate(auto: Boolean = false) {
        val rel = update ?: return
        if (updateProgress != null) return
        if (auto && android.os.Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            say(tx(R.string.update_allow_install)); return
        }
        viewModelScope.launch {
            try {
                updateProgress = 0
                val f = pendingApk?.takeIf { it.exists() && it.name.contains("${rel.build}") }
                    ?: Updater.download(ctx, repo.http, rel) { updateProgress = it }
                pendingApk = f
                updateProgress = null
                if (!Updater.install(ctx, f)) say(tx(R.string.update_allow_retry))
            } catch (e: Exception) {
                updateProgress = null
                say(tx(R.string.download_failed, e.message ?: ""))
            }
        }
    }

    /* ───────── pairing from phone ───────── */
    var pairingUrl by mutableStateOf<String?>(null); private set
    private var pairing: PairingServer? = null

    fun startPairing() {
        val srv = PairingServer { f ->
            viewModelScope.launch {
                val ok = when (f["t"]) {
                    "m3u" -> f["url"].orEmpty().takeIf { it.isNotBlank() }?.also { setUrl(it) }
                    else -> if (!f["host"].isNullOrBlank() && !f["user"].isNullOrBlank() && !f["pass"].isNullOrBlank()) {
                        setXtream(f["host"]!!, f["user"]!!, f["pass"]!!); "ok"
                    } else null
                }
                if (ok != null) { stopPairing(); say(tx(R.string.pair_received)) } else say(tx(R.string.pair_incomplete))
            }
        }
        val u = srv.start()
        if (u == null) { say(tx(R.string.pair_failed)); return }
        pairing = srv; pairingUrl = u
    }

    fun stopPairing() { pairing?.stop(); pairing = null; pairingUrl = null }

    override fun onCleared() { stopPairing(); super.onCleared() }

    /* ───────── profiles ───────── */
    var profile by mutableStateOf(store.currentProfile); private set
    var profileList by mutableStateOf(store.profiles()); private set

    private fun reloadProfileState() {
        profile = store.currentProfile; profileList = store.profiles()
        favs = store.favorites; history = store.history()
        tab = runCatching { Tab.valueOf(store.startSection) }.getOrDefault(Tab.Home)
        group = GROUP_ALL; query = ""
        parental.lock(); bump(); refreshRecs()
    }

    private fun doSwitch(id: String) { store.switchProfile(id); reloadProfileState(); screen = Screen.Main }

    /** Leaving a kids profile needs the PIN (when one is set), so a child cannot hop to an adult profile. */
    fun switchProfile(id: String) {
        if (id == profile.id) { screen = Screen.Main; return }
        if (profile.kids && store.hasPin && !parental.unlocked) pin = PinRequest(tx(R.string.pin_switch)) { doSwitch(id) }
        else doSwitch(id)
    }

    fun openProfiles() { reloadProfileState(); screen = Screen.Profiles }

    fun addProfile(name: String, kids: Boolean) { store.addProfile(name, kids); profileList = store.profiles() }

    fun deleteProfile(id: String) {
        requirePin(tx(R.string.pin_enter)) { store.deleteProfile(id); reloadProfileState() }
    }

    /* ───────── parental control ───────── */
    private fun guard(c: Channel, go: () -> Unit) {
        if (parental.needsPin(c)) pin = PinRequest(tx(R.string.pin_enter)) { go() } else go()
    }

    fun submitPin(code: String): Boolean {
        val req = pin ?: return false
        if (!parental.unlock(code)) {
            val w = parental.lockoutSeconds()
            if (w > 0) say(tx(R.string.pin_throttled, w))
            return false
        }
        pin = null; bump(); req.onOk()
        return true
    }

    /** Runs [go] directly when no PIN is set, otherwise after the PIN is verified. */
    fun requirePin(title: String, go: () -> Unit) {
        if (!store.hasPin || parental.unlocked) go() else pin = PinRequest(title) { go() }
    }

    fun setPin(code: String) { store.setPin(code); bump() }
    fun clearPin() { requirePin(tx(R.string.pin_clear)) { store.clearPin(); parental.lock(); bump() } }
    fun setLevel(m: Maturity) { requirePin(tx(R.string.pin_enter)) { store.parentalLevel = m; bump() } }
    fun lockNow() { parental.lock(); bump() }

    fun toggleGroupLock(g: String) {
        if (!store.hasPin) { say(tx(R.string.pin_set_first)); return }
        requirePin(tx(R.string.pin_enter)) {
            store.lockedGroups = if (g in store.lockedGroups) store.lockedGroups - g else store.lockedGroups + g
            bump()
        }
    }

    fun togglePinGroup(g: String) {
        store.pinnedGroups = if (g in store.pinnedGroups) store.pinnedGroups - g else store.pinnedGroups + g; bump()
    }

    fun hideGroup(g: String) {
        requirePin(tx(R.string.pin_enter)) { store.hiddenGroups = store.hiddenGroups + g; if (group == g) group = GROUP_ALL; bump() }
    }

    fun unhideAllGroups() { store.hiddenGroups = emptySet(); bump() }

    fun say(m: String) { message = m }

    /* ───────── favourites / custom groups / hiding ───────── */
    /** Movies and series saved for later (newest first). Observable so shelves and menus update at once. */
    var laterUrls by mutableStateOf(store.watchLater); private set

    fun toggleLater(c: Channel) {
        if (c.url in laterUrls) { store.removeWatchLater(c.url); say(tx(R.string.x_later_removed)) }
        else { store.addWatchLater(c.url); say(tx(R.string.x_later_added)) }
        laterUrls = store.watchLater
    }

    /** Leaves the list once the title has been started (series) or finished (movie). */
    private fun dropLater(url: String) {
        if (url in laterUrls) { store.removeWatchLater(url); laterUrls = store.watchLater }
    }

    fun toggleFav(c: Channel) {
        favs = if (c.url in favs) favs - c.url else favs + c.url
        store.favorites = favs
        refreshRecs()
    }

    fun hideChannel(c: Channel) { store.hiddenChannels = store.hiddenChannels + c.url; bump(); say(tx(R.string.channel_hidden)) }
    fun unhideAllChannels() { store.hiddenChannels = emptySet(); bump() }

    fun addToCustomGroup(name: String, c: Channel) {
        val m = LinkedHashMap(store.customGroups())
        m[name] = (m[name] ?: emptyList()) + c.url
        store.saveCustomGroups(m); bump()
        say(tx(R.string.added_to, name))
    }

    fun removeCustomGroup(name: String) {
        val m = LinkedHashMap(store.customGroups()); m.remove(name); store.saveCustomGroups(m)
        if (group == GROUP_CUSTOM + name) group = GROUP_ALL
        bump()
    }

    /* ───────── details (movies / series) ───────── */
    var detail by mutableStateOf<Channel?>(null); private set
    var info by mutableStateOf<VodInfo?>(null); private set
    var episodes by mutableStateOf<List<Episode>>(emptyList()); private set
    var epLoading by mutableStateOf(false); private set
    var season by mutableStateOf(1); private set
    /** Other titles from the library featuring the lead actors of the open title (needs TMDB). */
    var castWorks by mutableStateOf<List<Channel>>(emptyList()); private set
    /** Episode last focused / started in the gallery, so returning from playback lands on the same card. */
    var epFocusUrl: String? = null

    fun openDetail(c: Channel) = guard(c) {
        val k = kindOf(c)
        detail = c; info = repo.cachedInfo(c, k); episodes = emptyList(); season = 1; error = null; epFocusUrl = null; castWorks = emptyList()
        back = Screen.Main; screen = Screen.Detail
        viewModelScope.launch {
            val i = repo.vodInfo(c, k)
            info = i
            if (detail?.url == c.url && i != null) loadCastWorks(c, i)
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
    private suspend fun loadCastWorks(c: Channel, i: VodInfo) {
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
    fun providerCheck() {
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

    fun closeDetail() { restoreFocus = true; screen = Screen.Main }
    fun selectSeason(n: Int) { season = n; detail?.let { loadStills(it, n) } }

    private val stillsRequested = HashSet<String>()

    /** Fetches real TMDB stills for one season (lazy: only the season on screen) and merges them into [episodes]. */
    private fun loadStills(c: Channel, n: Int) {
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
    fun open(c: Channel) {
        when (kindOf(c)) {
            Kind.Live -> play(c)
            Kind.Series -> if (c.url.startsWith("series:")) openDetail(c) else play(c)
            Kind.Movie -> openDetail(c)
        }
    }

    /* ───────── playback ───────── */
    var current by mutableStateOf<Channel?>(null); private set
    var playMode by mutableStateOf(PlayMode.Live); private set
    var resumeAt by mutableStateOf(0L); private set
    var zapList by mutableStateOf<List<Channel>>(emptyList()); private set
    private var tried = HashSet<String>()

    val zapEnabled: Boolean get() = playMode == PlayMode.Live && zapList.isNotEmpty()

    fun play(c: Channel, mode: PlayMode? = null, startAt: Long = -1) = guard(c) {
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

    fun playEpisode(s: Channel, e: Episode) {
        // Remember the episode (image, number, title) and make it the series' current episode for Continue watching.
        store.saveEpMeta(e.url, EpMeta(s.url, s.name, e.season, e.num, e.title, VmLogic.episodeImage(e, episodes, s.logo)))
        store.setContCursor(s.url, e.url)
        dropLater(s.url)
        if (detail?.url == s.url && episodes.isNotEmpty()) epListCache[s.url] = episodes
        bump()
        play(Channel(-1, "${s.name} · ${tx(R.string.ep_label, e.season, e.num)}", e.url, s.logo, s.group), PlayMode.Vod)
    }

    /* ---------- Continue watching (movies + series episodes) ---------- */
    private val epListCache = HashMap<String, List<Episode>>()
    private val imageCache = HashMap<String, String?>()

    /** Newest first: unfinished movies, and one card per series showing its current episode with that episode's own image. */
    /** Series of the library by normalised name; only names that identify ONE series are usable. */
    private val uniqueSeriesByName: Map<String, Channel> by derivedStateOf {
        series.groupBy { LogoMatch.norm(it.name) }.filterValues { it.size == 1 }.mapValues { it.value[0] }   // raw list: rebuilt only when it is (re)loaded
    }

    /** History from before Continue watching existed has no episode metadata: rebuild it from the entry's name. */
    private fun legacyMeta(h: HistoryEntry): EpMeta? {
        if (h.kind != Kind.Series) return null
        val (name, season, num) = VmLogic.parseEpisodeName(h.name) ?: return null
        val s = uniqueSeriesByName[LogoMatch.norm(name)] ?: return null
        return EpMeta(s.url, s.name, season, num, "", null)
    }

    val continueItems: List<ContinueItem> by derivedStateOf {
        rev
        val out = ArrayList<ContinueItem>()
        val seen = HashSet<String>()
        for (h in history) {
            if (h.kind == Kind.Live) continue
            val meta = store.epMeta(h.url) ?: legacyMeta(h)
            if (meta == null) {
                if (h.kind == Kind.Movie && (h.progress > 0.02f || h.position > 15_000L) && parental.visible(histChannel(h)))
                    out += ContinueItem("m:" + h.url, Kind.Movie, h.name, "", "", null, h.progress, histChannel(h), null, h.url, 0, 0, h.group, h.last, h.logo)
                continue
            }
            if (!seen.add(meta.seriesUrl)) continue
            val cur = store.contCursor(meta.seriesUrl)
            val epUrl = cur?.first ?: h.url
            val m = store.epMeta(epUrl) ?: (if (epUrl == h.url) meta else null) ?: continue
            val th = historyByUrl[epUrl]
            val picked = cur != null && cur.first != h.url
            val inProgress = th != null && !th.finished && (th.progress > 0.02f || th.position > 15_000L)
            // The last episode of a series being finished does not remove it from the list; it just stays as it is.
            if (!inProgress && !picked && th?.finished != true) continue
            if (!parental.visible(Channel(-1, m.seriesName, m.seriesUrl, h.logo, h.group))) continue
            out += ContinueItem(
                "s:" + m.seriesUrl, Kind.Series, m.seriesName, m.title, tx(R.string.ep_label, m.season, m.num), m.image?.takeIf { img -> img != h.logo },
                if (inProgress) th!!.progress else 0f,
                Channel(-1, "${m.seriesName} · ${tx(R.string.ep_label, m.season, m.num)}", epUrl, h.logo, h.group),
                m.seriesUrl, epUrl, m.season, m.num, h.group, maxOf(th?.last ?: 0L, cur?.second ?: 0L, h.last), h.logo
            )
        }
        out.sortedByDescending { it.last }.take(30)
    }

    /** Landscape image for a card that has none yet: a movie's backdrop, or the episode's still (TVmaze / TMDB). */
    suspend fun continueImage(it: ContinueItem): String? {
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

    fun playContinue(it: ContinueItem) {
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
            } catch (_: Exception) { }
        }
    }

    /** Moves a series card to the next (+1) or previous (-1) episode. Tapping the card then plays that episode. */
    fun continueStep(it: ContinueItem, delta: Int) {
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
    private fun advanceAfterFinish(c: Channel) {
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

    fun continueMenu(it: ContinueItem) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (it.seriesUrl != null) {
            items += tx(R.string.x_cont_next) to { continueStep(it, 1) }
            items += tx(R.string.x_cont_prev) to { continueStep(it, -1) }
        }
        items += tx(R.string.details) to { openDetail(Channel(-1, it.title, it.seriesUrl ?: it.episodeUrl, it.poster, it.group)) }
        menu = MenuRequest(if (it.badge.isNotEmpty()) "${it.title} · ${it.badge}" else it.title, items)
    }

    /** Episode that follows the one currently playing, if any (for auto-next). */
    fun nextEpisodeOf(c: Channel): Episode? {
        return VmLogic.nextEpisode(episodes, c.url)
    }

    /** Episode before the current one, if any. */
    fun previousEpisodeOf(c: Channel): Episode? {
        return VmLogic.previousEpisode(episodes, c.url)
    }

    fun hasNextEpisode(c: Channel): Boolean = nextEpisodeOf(c) != null
    fun hasPreviousEpisode(c: Channel): Boolean = previousEpisodeOf(c) != null

    fun playNext() {
        val c = current ?: return
        val d = detail ?: return
        nextEpisodeOf(c)?.let { playEpisode(d, it) }
    }

    fun playPrevious() {
        val c = current ?: return
        val d = detail ?: return
        previousEpisodeOf(c)?.let { playEpisode(d, it) }
    }

    /** Next/previous item for the player's transport row: channels in live, episodes in a series. */
    fun playNeighbor(delta: Int) {
        if (playMode == PlayMode.Live) zap(delta)
        else if (delta > 0) playNext() else playPrevious()
    }

    fun hasNeighbor(delta: Int): Boolean {
        if (playMode == PlayMode.Live) return zapEnabled
        val c = current ?: return false
        return if (delta > 0) hasNextEpisode(c) else hasPreviousEpisode(c)
    }

    fun playCatchup(c: Channel, p: Prog) {
        val u = repo.catchupUrl(c, p)
        if (u == null) { say(tx(R.string.no_catchup)); return }
        play(Channel(-1, "${c.name} · ${p.title}", u, c.logo, c.group), PlayMode.Catchup, 0)
    }

    fun playRecording(r: Recording) {
        val f = r.file ?: return
        play(Channel(-1, r.title, "file://$f", null, r.channelName), PlayMode.Recording, 0)
    }

    fun stop() {
        sleepAt = 0L; history = store.history(); restoreFocus = true; screen = back
        // Re-deriving every list (tens of thousands of channels) on the main thread made the way back from the player
        // take seconds. History is observable state, so shelves refresh on their own; lists only depend on health when these are on.
        if (store.hideDead || store.mergeDuplicates) bump()
        refreshRecs()
    }

    fun zap(delta: Int) {
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
    fun zapToNumber(n: Int) {
        val list = zapList
        val c = list.firstOrNull { it.number == n } ?: list.getOrNull(n - 1) ?: return
        if (parental.needsPin(c)) return
        tried = hashSetOf(c.url); current = c
    }

    /** Switch to a specific channel while the player is open (side panel / last channel). */
    fun switchTo(c: Channel) {
        if (parental.needsPin(c)) return
        tried = hashSetOf(c.url); current = c
    }

    /** Same channel from another source (HD/FHD/SD duplicates), used by manual switch and auto-failover. */
    fun alternativesOf(c: Channel): List<Channel> = health.alternatives(c, all)

    fun failover(): Boolean {
        val c = current ?: return false
        val next = alternativesOf(c).firstOrNull { it.url !in tried } ?: return false
        tried += next.url
        current = next
        return true
    }

    fun useAlternative(c: Channel) { tried += c.url; current = c }

    /** Optional: warm the next and previous channel of the zap list (see [Prefetch]). */
    fun prefetchNeighbors() {
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
    fun problemReport(): List<String> = store.worstHealth(15).map { (u, r) ->
        val name = all.firstOrNull { it.url == u }?.name ?: u.takeLast(30)
        tx(R.string.x_problem_line, name, tx(if (r.state == Health.Dead) R.string.x_word_dead else R.string.x_word_slow), r.failures)
    }


    fun noteStart(c: Channel) {
        val old = store.historyOf(c.url)
        store.recordWatch(c, kindOf(c), old?.position ?: 0, old?.duration ?: 0, true)
    }

    fun saveProgress(c: Channel, pos: Long, dur: Long) {
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

    /* ───────── Trakt ───────── */
    private var traktJob: kotlinx.coroutines.Job? = null

    /** Starts the device-code link: shows the code on screen and waits for approval on trakt.tv/activate. */
    fun traktLink() {
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

    fun traktUnlink() { Trakt.unlink(store); say(tx(R.string.trakt_unlinked)); bump() }

    /** Called once when a movie / episode is finished: marks it watched on Trakt (silently ignored on failure). */
    private fun traktMarkWatched(c: Channel) {
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

    /* ───────── resume from the Android TV home screen ───────── */
    private var pendingResume: String? = null

    /** Called when the user taps a "Watch Next" tile; waits for the channel list when the app is still starting. */
    fun resumeFromWatchNext(url: String) {
        if (all.isEmpty()) { pendingResume = url; return }
        playResume(url)
    }

    private fun playResume(token: String) {
        // token is either an opaque WatchNext key or (older tiles) the raw url
        val h = store.historyOf(token) ?: store.history().firstOrNull { WatchNext.keyOf(it.url) == token } ?: return
        play(histChannel(h))
    }

    fun clearHistory() { store.clearHistory(); WatchNext.clearAll(ctx); history = emptyList(); recs = emptyList(); because = null; bump() }

    /* ───────── recording / reminders ───────── */
    fun record(c: Channel, p: Prog) {
        val clash = recCtl.schedule(c, p)
        say(recordMsg(clash != null, clash == true))
    }

    fun autoRecord(c: Channel, p: Prog) {
        if (c.url !in favs) toggleFav(c)
        store.autoRecordTitles = store.autoRecordTitles + p.title.lowercase()
        Recorder.syncAuto(ctx, store, all, epg)
        recCtl.refresh()
        say(tx(R.string.rec_all_eps, p.title))
    }

    fun remind(c: Channel, p: Prog) { Recorder.remind(ctx, store, c, p); say(tx(R.string.remind_set)) }

    fun cancelRecording(r: Recording) { recCtl.cancel(r) }
    fun deleteRecording(r: Recording) { recCtl.delete(r) }
    fun refreshRecordings() { recCtl.refresh() }

    /* ───────── health ───────── */
    var healthStatus by mutableStateOf<String?>(null); private set

    /** Throttled probe of the favourites (or the current category, capped). */
    fun checkHealth(favoritesOnly: Boolean) {
        if (healthStatus != null) return
        val list = (if (favoritesOnly) liveList.filter { it.url in favs } else visible).take(150).map { it.url }
        if (list.isEmpty()) { say(tx(R.string.check_none)); return }
        viewModelScope.launch {
            healthStatus = "0/${list.size}"
            val dead = health.check(list, 3) { d, t -> healthStatus = "$d/$t" }
            healthStatus = null; bump()
            say(tx(R.string.check_done, dead, list.size))
        }
    }

    fun clearHealth() { store.clearHealth(); bump() }

    /** (healthy, total) among the links that have actually been tested. */
    val healthSummary by derivedStateOf { rev; store.healthStats() }

    /* ───────── multi-view ───────── */
    var multi by mutableStateOf<List<Channel>>(emptyList()); private set

    fun toggleMulti(c: Channel) {
        multi = if (multi.any { it.url == c.url }) multi.filter { it.url != c.url }
        else if (multi.size >= 4) { say(tx(R.string.multi_max)); multi } else multi + c
        say(tx(R.string.multi_count, multi.size))
    }

    fun openMulti() {
        if (multi.size < 2) { say(tx(R.string.multi_min)); return }
        back = Screen.Main; screen = Screen.MultiView
    }

    fun closeMulti() { restoreFocus = true; screen = Screen.Main }
    fun clearMulti() { multi = emptyList() }

    /* ───────── manual EPG match ───────── */
    fun setEpgOverride(c: Channel, xmlId: String) {
        store.setEpgOverride(c.url, xmlId.ifBlank { null })
        viewModelScope.launch { epg = repo.loadEpg(all, false); bump() }
    }

    /* ───────── context menu for a channel / title ───────── */
    fun channelMenu(c: Channel) {
        val k = kindOf(c)
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += (if (c.url in favs) tx(R.string.menu_fav_remove) else tx(R.string.menu_fav_add)) to { toggleFav(c) }
        if (k != Kind.Live) items += (if (c.url in laterUrls) tx(R.string.x_later_remove) else tx(R.string.x_later_add)) to { toggleLater(c) }
        if (k == Kind.Live) {
            items += tx(R.string.menu_multi, multi.size) to { toggleMulti(c) }
            items += tx(R.string.menu_add_group) to {
                val names = store.customGroups().keys.toList()
                menu = MenuRequest(tx(R.string.menu_groups),
                    names.map { n -> n to { addToCustomGroup(n, c) } } +
                        (tx(R.string.menu_new_group) to { prompt = PromptRequest(tx(R.string.prompt_group_name), "") { n -> if (n.isNotBlank()) addToCustomGroup(n.trim(), c) } })
                )
            }
            val alts = alternativesOf(c)
            if (alts.isNotEmpty()) items += tx(R.string.menu_alt_sources, alts.size) to {
                menu = MenuRequest(tx(R.string.alt_sources), alts.map { a -> (a.name + " · " + health.state(a.url).name) to { play(a) } })
            }
            items += tx(R.string.menu_epg_match) to {
                prompt = PromptRequest(tx(R.string.prompt_epg_id), store.epgOverrides()[c.url] ?: c.tvgId ?: "") { id -> setEpgOverride(c, id.trim()) }
            }
        }
        items += tx(R.string.menu_hide) to { hideChannel(c) }
        menu = MenuRequest(c.name, items)
    }

    fun groupMenu(g: String) {
        if (g == GROUP_ALL || g == GROUP_FAV || g == GROUP_RECENT || g == GROUP_NEW || g == GROUP_CONT) return
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (g.startsWith(GROUP_CUSTOM)) {
            items += tx(R.string.menu_delete_group) to { removeCustomGroup(g.removePrefix(GROUP_CUSTOM)) }
        } else {
            items += (if (g in store.pinnedGroups) tx(R.string.menu_unpin) else tx(R.string.menu_pin)) to { togglePinGroup(g) }
            items += (if (g in store.lockedGroups) tx(R.string.menu_unlock) else tx(R.string.menu_lock)) to { toggleGroupLock(g) }
            items += tx(R.string.menu_hide_group) to { hideGroup(g) }
        }
        menu = MenuRequest(groupLabel(g), items)
    }
}

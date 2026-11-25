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
    internal val ctx: Application = app

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
        internal set(v) { screenState = v; syncTheme() }

    /** User's light/dark choice. Video screens are always dark so controls and overlays stay readable. */
    var darkPref by mutableStateOf(store.darkMode); internal set
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
    internal val gridStates = HashMap<String, androidx.compose.foundation.lazy.grid.LazyGridState>()
    internal val listStates = HashMap<String, androidx.compose.foundation.lazy.LazyListState>()
    fun gridState(key: String) = gridStates.getOrPut(key) { androidx.compose.foundation.lazy.grid.LazyGridState() }
    fun listState(key: String) = listStates.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState() }
    var tab by mutableStateOf(runCatching { Tab.valueOf(store.startSection) }.getOrDefault(Tab.Home))
        internal set
    internal var back = Screen.Main

    /** Bumped whenever something that affects visibility changes (unlock, hide, health…). */
    var rev by mutableIntStateOf(0)
        internal set
    internal fun bump() { rev++ }

    /** Re-derives the lists after a setting that changes what they show. */
    fun listsChanged() = bump()

    var inPip by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var pin by mutableStateOf<PinRequest?>(null)
    var menu by mutableStateOf<MenuRequest?>(null)
    var titleMenu by mutableStateOf<Channel?>(null)   // long press on a movie / series card
    var prompt by mutableStateOf<PromptRequest?>(null)

    /* ───────── content ───────── */
    var all by mutableStateOf<List<Channel>>(emptyList()); internal set
    var movies by mutableStateOf<List<Channel>>(emptyList()); internal set
    var series by mutableStateOf<List<Channel>>(emptyList()); internal set
    var epg by mutableStateOf<Map<String, List<Prog>>>(emptyMap()); internal set
    var loading by mutableStateOf(false); internal set
    var vodLoading by mutableStateOf(false); internal set
    var error by mutableStateOf<String?>(null); internal set

    var favs by mutableStateOf(store.favorites); internal set
    var history by mutableStateOf(store.history()); internal set
    /** url → history entry, rebuilt only when history changes (cards look progress up here instead of scanning the list). */
    val historyByUrl: Map<String, HistoryEntry> by derivedStateOf { history.associateBy { it.url } }
    internal val recCtl = RecordingsController(app, store)
    val recordings: List<Recording> get() = recCtl.items
    var recs by mutableStateOf<List<Channel>>(emptyList()); internal set
    var because by mutableStateOf<Pair<String, List<Channel>>?>(null); internal set

    var group by mutableStateOf(GROUP_ALL)
    var query by mutableStateOf("")

    val savedUrl: String get() = repo.playlistUrl
    val hasXtream: Boolean get() = repo.hasXtream

    /** The start-up splash is shown once per process; it ends when [startupReady] holds (or after its time cap). */
    var splashDone by mutableStateOf(false)

    /** Observable copy of the "hide names under posters" option, so every poster updates the moment it is switched. */
    var hidePosterTitles by mutableStateOf(store.hidePosterTitles); internal set
    fun applyHidePosterTitles(v: Boolean) { hidePosterTitles = v; store.hidePosterTitles = v }
    var heroRotate by mutableStateOf(store.heroRotate); internal set
    fun applyHeroRotate(v: Boolean) { heroRotate = v; store.heroRotate = v }

    /** The first screen has something real to show: cached lists loaded, or an error / the setup screen is needed. */
    fun startupReady(): Boolean = screen == Screen.Setup || error != null || (!loading && all.isNotEmpty() && !vodLoading)

    internal fun ok(c: Channel, live: Boolean, hidden: Set<String>): Boolean =
        parental.visible(c) && c.url !in hidden && !(live && store.hideDead && health.isDead(c.url))

    /**
     * The filtered lists can be prepared on a background thread: [warmLists] computes them from plain values (the raw list,
     * `rev`, the stores) and keeps the result tagged with the exact inputs it was built from. The derived lists below use it
     * when the tag still matches, and otherwise compute as before, so a stale result can never be shown.
     */
    private class Pre(val src: List<Channel>, val rev: Int, val list: List<Channel>)
    @Volatile private var preLive: Pre? = null
    @Volatile private var preMovie: Pre? = null
    @Volatile private var preSeries: Pre? = null

    private fun computeLive(src: List<Channel>): List<Channel> {
        val h = store.hiddenChannels
        val l = src.filter { ok(it, true, h) }
        return if (store.mergeDuplicates) dedupeSources(l) else l
    }
    /** Copies per kept movie / series card (only entries with more than one), filled when the lists are built. */
    internal val versionCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private fun computeVod(src: List<Channel>): List<Channel> {
        val h = store.hiddenChannels
        val l = src.filter { ok(it, false, h) }
        return if (store.mergeVodDuplicates) VmLogic.dedupeVod(l, versionCounts) else l
    }

    suspend fun warmLists() {
        val r = rev; val a = all; val m = movies; val sr = series
        withContext(Dispatchers.Default) {
            runCatching {
                if (a.isNotEmpty()) preLive = Pre(a, r, computeLive(a))
                if (m.isNotEmpty()) preMovie = Pre(m, r, computeVod(m))
                if (sr.isNotEmpty()) preSeries = Pre(sr, r, computeVod(sr))
            }
        }
    }

    val liveList by derivedStateOf { val r = rev; val a = all; preLive?.takeIf { it.src === a && it.rev == r }?.list ?: computeLive(a) }

    /** Keeps one entry per channel name (quality tags ignored), preferring the healthiest source. Others stay available as alternatives. */
    private fun dedupeSources(l: List<Channel>): List<Channel> {
        fun rank(c: Channel) = when (health.state(c.url)) { Health.Ok -> 0; Health.Unknown -> 1; Health.Slow -> 2; Health.Dead -> 3 }
        return VmLogic.dedupeSources(l, ::rank)
    }
    val movieList by derivedStateOf { val r = rev; val m = movies; preMovie?.takeIf { it.src === m && it.rev == r }?.list ?: computeVod(m) }
    val seriesList by derivedStateOf { val r = rev; val sr = series; preSeries?.takeIf { it.src === sr && it.rev == r }?.list ?: computeVod(sr) }

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
    var searchResults by mutableStateOf<List<Channel>>(emptyList()); internal set
    /** Programs (now or upcoming) whose title matches the search, taken from the EPG. */
    var progResults by mutableStateOf<List<Pair<Channel, Prog>>>(emptyList()); internal set
    private var searchJob: kotlinx.coroutines.Job? = null

    fun runSearch() {
        searchJob?.cancel()
        val q = query
        if (q.isBlank()) { searchResults = emptyList(); progResults = emptyList(); return }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250)   // debounce typing
            val h = store.hiddenChannels
            val rows = withContext(Dispatchers.IO) { repo.dao.search(q, slot = repo.slotId()) }
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

    /** TMDB daily trending + top-rated charts (null until loaded / without a TMDB key). */
    var tmdbCharts by mutableStateOf<TmdbCharts.Charts?>(null); internal set
    internal var chartsAt = 0L
    // Title indexes used to match the TMDB charts. Built ONCE per loaded list on a background thread. (They used to be rebuilt
    // on the UI thread after every state bump, normalising every title of a 50k-item library each time: it froze TV boxes.)
    private var movieTitleIdx by mutableStateOf<Map<String, List<Pair<Channel, Int?>>>>(emptyMap())
    private var seriesTitleIdx by mutableStateOf<Map<String, List<Pair<Channel, Int?>>>>(emptyMap())

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

    /** Upcoming (not yet released) titles from TMDB; the rows below hide whatever the library already has. */
    var upcomingRaw by mutableStateOf<List<UpcomingItem>>(emptyList()); internal set
    internal var upcomingAt = 0L
    /** false = most popular first (default), true = soonest release first. Switchable on phone / tablet only. */
    var upcomingByDate by mutableStateOf(false)

    private fun upcomingRow(movie: Boolean): List<UpcomingItem> {
        rev
        val idx = if (movie) movieTitleIdx else seriesTitleIdx
        // A title the library already has has moved from "coming soon" to "available": it is removed from the row, with no badge or marker.
        val kept = upcomingRaw.filter { u ->
            u.movie == movie && TmdbCharts.pick(idx, listOf(TmdbCharts.Entry(u.names, u.year, null, null)), 1) { true }.first.isEmpty()
        }
        return (if (upcomingByDate) kept.sortedBy { it.date } else kept.sortedByDescending { it.popularity }).take(20)
    }
    val upcomingMovies by derivedStateOf { upcomingRow(true) }
    val upcomingSeries by derivedStateOf { upcomingRow(false) }

    /** Item currently focused on Home; drives the big preview on TV/tablet. */
    var homeFocus by mutableStateOf<Channel?>(null)
    /** TV home shows one row at a time: index of the visible row (continue-watching first, then the shelves). Survives opening a title. */
    var homeRow by mutableIntStateOf(0)
    var homeInfo by mutableStateOf<VodInfo?>(null); internal set
    var homeInfoUrl: String? = null; internal set

    internal val recsFile get() = java.io.File(ctx.filesDir, "home_recs_${store.currentProfile.id}.json")

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
        NewEpisodesWorker.schedule(app)
        if (repo.playlistUrl.isNotBlank()) { load(false); refreshRecs() }
        checkUpdate(false)
        if (store.tmdbKey.isNotBlank()) TmdbCharts.loadCached(ctx)?.let { (c, at) -> tmdbCharts = c; chartsAt = at }   // instant rows, refreshed below when older than 6 h
        if (store.tmdbKey.isNotBlank()) Upcoming.loadCached(ctx)?.let { (l, at) -> upcomingRaw = l; upcomingAt = at }
        refreshTmdbCharts()
        refreshUpcoming()
        viewModelScope.launch { androidx.compose.runtime.snapshotFlow { movies }.collect { l -> movieTitleIdx = withContext(Dispatchers.Default) { TmdbCharts.indexOf(l) } } }
        viewModelScope.launch { androidx.compose.runtime.snapshotFlow { series }.collect { l -> seriesTitleIdx = withContext(Dispatchers.Default) { TmdbCharts.indexOf(l) } } }
        refreshInBackgroundIfStale()
        viewModelScope.launch { while (true) { kotlinx.coroutines.delay(20 * 60_000L); checkUpdate(false) } }
    }

    /** Tabs whose VOD list is being fetched right now (prevents duplicate / parallel loads of the same list). */
    internal val vodActive = HashSet<Tab>()
    internal var vodBusy = 0
    internal var clearJob: kotlinx.coroutines.Job? = null

    var diag by mutableStateOf<String?>(null); internal set
    var diagBusy by mutableStateOf(false); internal set

    /* ───────── sleep timer / daily limit / suggestions ───────── */
    var sleepAt by mutableStateOf(0L)

    /* ───────── in-app update ───────── */
    var update by mutableStateOf<Updater.Release?>(null); internal set
    var updateProgress by mutableStateOf<Int?>(null); internal set
    internal var pendingApk: java.io.File? = null

    /* ───────── pairing from phone ───────── */
    var pairingUrl by mutableStateOf<String?>(null); internal set
    internal var pairing: PairingServer? = null

    override fun onCleared() { stopPairing(); super.onCleared() }

    /* ───────── profiles ───────── */
    var profile by mutableStateOf(store.currentProfile); internal set
    var profileList by mutableStateOf(store.profiles()); internal set

    /* ───────── favourites / custom groups / hiding ───────── */
    /** Movies and series saved for later (newest first). Observable so shelves and menus update at once. */
    var laterUrls by mutableStateOf(store.watchLater); internal set

    /* ───────── details (movies / series) ───────── */
    var detail by mutableStateOf<Channel?>(null); internal set
    var info by mutableStateOf<VodInfo?>(null); internal set
    var episodes by mutableStateOf<List<Episode>>(emptyList()); internal set
    var epLoading by mutableStateOf(false); internal set
    var season by mutableStateOf(1); internal set
    /** Other titles from the library featuring the lead actors of the open title (needs TMDB). */
    var castWorks by mutableStateOf<List<Channel>>(emptyList()); internal set
    var similarWorks by mutableStateOf<List<Channel>>(emptyList()); internal set
    /** Next episode of the open series (air date / time), null when unknown or the series has ended. */
    var nextAir by mutableStateOf<NextAir?>(null); internal set
    /** Episode last focused / started in the gallery, so returning from playback lands on the same card. */
    var epFocusUrl: String? = null

    /** Titles opened before the current one, so Back from a suggested title returns to the page you came from. */
    internal val detailStack = ArrayList<Channel>()
    /** The list the open title came from (shelf, tab list, search...): left / right on the detail page moves along it. */
    internal var neighborList: List<Channel> = emptyList()

    internal val stillsRequested = HashSet<String>()

    /* ───────── playback ───────── */
    var current by mutableStateOf<Channel?>(null); internal set
    var playMode by mutableStateOf(PlayMode.Live); internal set
    var resumeAt by mutableStateOf(0L); internal set
    var zapList by mutableStateOf<List<Channel>>(emptyList()); internal set
    internal var tried = HashSet<String>()

    val zapEnabled: Boolean get() = playMode == PlayMode.Live && zapList.isNotEmpty()

    /* ---------- Continue watching (movies + series episodes) ---------- */
    /** Episode lists already fetched, newest used last; capped so a long session cannot keep every series in memory. Safe to call from any thread. */
    internal val epListCache: MutableMap<String, List<Episode>> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, List<Episode>>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Episode>>?) = size > 60
        }
    )

    /** Newest first: unfinished movies, and one card per series showing its current episode with that episode's own image. */
    /** Series of the library by normalised name; only names that identify ONE series are usable. */
    internal val uniqueSeriesByName: Map<String, Channel> by derivedStateOf {
        series.groupBy { LogoMatch.norm(it.name) }.filterValues { it.size == 1 }.mapValues { it.value[0] }   // raw list: rebuilt only when it is (re)loaded
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

    /* ───────── Trakt ───────── */
    internal var traktJob: kotlinx.coroutines.Job? = null

    /* ───────── resume from the Android TV home screen ───────── */
    internal var pendingResume: String? = null

    /** Called when the user taps a "Watch Next" tile; waits for the channel list when the app is still starting. */
    /** The system killed the process while the app was in the background: put the viewer back on the same tab / title once the lists are loaded. */
    internal var pendingRestore: (() -> Unit)? = null

    /* ───────── health ───────── */
    var healthStatus by mutableStateOf<String?>(null); internal set

    /* ───────── multi-view ───────── */
    var multi by mutableStateOf<List<Channel>>(emptyList()); internal set

}

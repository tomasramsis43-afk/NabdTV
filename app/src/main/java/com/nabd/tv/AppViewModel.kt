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
enum class Tab(val label: String) {
    Home("الرئيسية"), Live("مباشر"), Movies("أفلام"), Series("مسلسلات"), Guide("الدليل"), Recordings("التسجيلات")
}
enum class PlayMode { Live, Vod, Catchup, Recording }

const val GROUP_ALL = "الكل"
const val GROUP_FAV = "★ المفضلة"
const val GROUP_RECENT = "◷ شاهدت مؤخراً"
const val GROUP_NEW = "🆕 المضاف حديثاً"
private const val NEW_LIMIT = 300
const val GROUP_CUSTOM = "◆ "

class PinRequest(val title: String, val onOk: () -> Unit)
class MenuRequest(val title: String, val items: List<Pair<String, () -> Unit>>)
class PromptRequest(val title: String, val initial: String, val onOk: (String) -> Unit)

fun kindOf(c: Channel): Kind = when {
    c.url.startsWith("series:") || c.url.contains("/series/") -> Kind.Series
    c.url.contains("/movie/") -> Kind.Movie
    else -> Kind.Live
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application = app
    val store = Store(app)
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
    private fun syncTheme() { Ui.dark = darkPref || screenState == Screen.Player || screenState == Screen.MultiView }
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
    var recordings by mutableStateOf(store.recordings()); private set
    var recs by mutableStateOf<List<Channel>>(emptyList()); private set
    var because by mutableStateOf<Pair<String, List<Channel>>?>(null); private set

    var group by mutableStateOf(GROUP_ALL)
    var query by mutableStateOf("")

    val savedUrl: String get() = repo.playlistUrl
    val hasXtream: Boolean get() = repo.hasXtream

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
        val best = HashMap<String, Channel>()
        for (c in l) {
            val k = Classifier.normalize(c.name)
            if (k.isBlank()) continue
            val b = best[k]
            if (b == null || rank(c) < rank(b)) best[k] = c
        }
        return l.filter { val k = Classifier.normalize(it.name); k.isBlank() || best[k]?.url == it.url }
    }
    val movieList by derivedStateOf { rev; val h = store.hiddenChannels; movies.filter { ok(it, false, h) } }
    val seriesList by derivedStateOf { rev; val h = store.hiddenChannels; series.filter { ok(it, false, h) } }

    private val base: List<Channel>
        get() = when (tab) { Tab.Movies -> movieList; Tab.Series -> seriesList; else -> liveList }

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
        if (tab == Tab.Live) {
            special += GROUP_RECENT
            special += store.customGroups().keys.map { GROUP_CUSTOM + it }
        }
        special + ordered
    }

    fun groupLabel(g: String): String =
        if (g.startsWith("★") || g.startsWith("◷") || g.startsWith("◆") || g.startsWith("🆕") || g == GROUP_ALL) g
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

    val shelves by derivedStateOf {
        rev
        val out = ArrayList<Shelf>()
        val cont = history.filter { it.kind != Kind.Live && !it.finished && it.progress > 0.02f && parental.visible(histChannel(it)) }
            .take(20).map { histChannel(it) }
        if (cont.isNotEmpty()) out += Shelf("متابعة المشاهدة", cont)
        if (recs.isNotEmpty()) out += Shelf("مقترح لك", recs)
        because?.let { (n, l) -> out += Shelf("لأنك شاهدت $n", l) }
        val favLive = liveList.filter { it.url in favs }.take(30)
        if (favLive.isNotEmpty()) out += Shelf("قنواتك المفضلة", favLive)
        val onAir = liveList.filter { nowNext(it).first != null }.take(30)
        if (onAir.isNotEmpty()) out += Shelf("على الهواء الآن", onAir)
        val recentLive = history.filter { it.kind == Kind.Live }.take(20).map { histChannel(it) }.filter { parental.visible(it) }
        if (recentLive.isNotEmpty()) out += Shelf("شاهدت مؤخراً", recentLive)
        val newMovies = movieList.filter { it.added > 0 }.sortedByDescending { it.added }.take(30)
        if (newMovies.isNotEmpty()) out += Shelf("🆕 أفلام أُضيفت حديثاً", newMovies)
        val newSeries = seriesList.filter { it.added > 0 }.sortedByDescending { it.added }.take(30)
        if (newSeries.isNotEmpty()) out += Shelf("🆕 مسلسلات أُضيفت حديثاً", newSeries)
        if (movieList.isNotEmpty()) out += Shelf("أفلام", movieList.take(30))
        if (seriesList.isNotEmpty()) out += Shelf("مسلسلات", seriesList.take(30))
        out
    }

    val hero: Channel? get() = shelves.firstOrNull { it.title == "مقترح لك" }?.items?.firstOrNull()
        ?: shelves.firstOrNull { it.title == "متابعة المشاهدة" }?.items?.firstOrNull()
        ?: movieList.firstOrNull() ?: liveList.firstOrNull()

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
        val l = epg[c.url] ?: return null to null
        val t = System.currentTimeMillis()
        val i = l.indexOfFirst { t >= it.start && t < it.stop }
        return if (i < 0) null to null else l[i] to l.getOrNull(i + 1)
    }

    var guideChannel by mutableStateOf<Channel?>(null)
    val guideChannels by derivedStateOf { rev; liveList.filter { epg.containsKey(it.url) }.take(600) }

    /* ───────── loading ───────── */
    init {
        syncTheme()
        RefreshWorker.schedule(app)
        if (repo.playlistUrl.isNotBlank()) { load(false); refreshRecs() }
        checkUpdate(false)
        viewModelScope.launch { while (true) { kotlinx.coroutines.delay(20 * 60_000L); checkUpdate(false) } }
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "تعذّر الوصول للسيرفر (الاسم غير صحيح أو لا يوجد إنترنت)"
        is java.net.SocketTimeoutException -> "انتهت مهلة الاتصال بالسيرفر"
        is java.net.ConnectException -> "السيرفر رفض الاتصال، تأكد من العنوان والبورت"
        is IllegalArgumentException -> "العنوان غير صحيح: " + (e.message ?: "")
        is org.json.JSONException -> "رد غير متوقع من السيرفر (الخدمة غير مفعّلة في اشتراكك أو الرد تالف)"
        else -> e.message ?: "خطأ غير معروف"
    }

    fun load(force: Boolean) {
        viewModelScope.launch {
            loading = true; error = null
            try {
                all = repo.load(force)
                group = GROUP_ALL
                screen = Screen.Main
                preloadVod()
                pendingResume?.let { pendingResume = null; playResume(it) }
                launch {
                    epg = repo.loadEpg(all, force)
                    Recorder.syncAuto(ctx, store, all, epg)
                    recordings = store.recordings()
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
            if (!silent) error = "الذاكرة لا تكفي لتحميل قائمة بهذا الحجم"
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
        if (t == Tab.Recordings) recordings = store.recordings()
    }

    fun refresh() {
        if (tab == Tab.Movies || tab == Tab.Series) loadVod(tab, true)
        else if (tab == Tab.Home && hasXtream) refreshAccount()
        else load(true)
    }

    /** Re-reads everything from the provider: account status, channels, movies, series and the guide. */
    fun refreshAccount() {
        viewModelScope.launch {
            say("جارٍ تحديث الحساب من السيرفر...")
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
    fun openSettings() { if (profile.kids) requirePin("أدخل الرمز السري") { screen = Screen.Settings } else screen = Screen.Settings }
    fun closeSettings() { restoreFocus = true; screen = Screen.Main; bump() }

    var diag by mutableStateOf<String?>(null); private set
    var diagBusy by mutableStateOf(false); private set
    fun runDiag() {
        viewModelScope.launch {
            diagBusy = true; diag = null
            diag = try { repo.diagnose() } catch (e: Exception) { "فشل الفحص: ${e.message}" }
            diagBusy = false
        }
    }

    /* ───────── sleep timer / daily limit / suggestions ───────── */
    var sleepAt by mutableStateOf(0L)

    fun limitReached(): Boolean {
        val lim = store.limitMinutes
        return lim > 0 && store.hasPin && !parental.unlocked && store.watchedSeconds() >= lim * 60
    }

    /** Called every 30 s while something is playing. */
    fun tickWatch(sec: Int) {
        store.addWatched(sec)
        if (limitReached() && screen == Screen.Player) {
            stop()
            pin = PinRequest("انتهى وقت المشاهدة اليوم. أدخل الرمز السري للمتابعة") { }
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
        if (picks.isEmpty()) { say("لا توجد اقتراحات بعد"); return }
        menu = MenuRequest("ماذا أشاهد؟", picks.map { c -> (if (kindOf(c) == Kind.Live) "📺 " else "🎬 ") + c.name to { open(c) } })
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
                if (manual) say(if (update == null) "أنت على آخر إصدار" else "يوجد إصدار جديد ${update!!.tag}")
            } catch (e: Exception) {
                if (manual) say("تعذّر فحص التحديث: ${e.message}")
            }
        }
    }

    fun installUpdate(auto: Boolean = false) {
        val rel = update ?: return
        if (updateProgress != null) return
        if (auto && android.os.Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            say("لتفعيل التحديث التلقائي: اسمح للتطبيق بالتثبيت من الإعدادات"); return
        }
        viewModelScope.launch {
            try {
                updateProgress = 0
                val f = pendingApk?.takeIf { it.exists() && it.name.contains("${rel.build}") }
                    ?: Updater.download(ctx, repo.http, rel) { updateProgress = it }
                pendingApk = f
                updateProgress = null
                if (!Updater.install(ctx, f)) say("اسمح للتطبيق بالتثبيت ثم اضغط تحديث مرة أخرى")
            } catch (e: Exception) {
                updateProgress = null
                say("فشل التنزيل: ${e.message}")
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
                if (ok != null) { stopPairing(); say("وصلت البيانات من الموبايل") } else say("البيانات المرسلة ناقصة")
            }
        }
        val u = srv.start()
        if (u == null) { say("تعذّر بدء الإعداد: تأكد أن الجهاز على شبكة Wi-Fi"); return }
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
        if (profile.kids && store.hasPin && !parental.unlocked) pin = PinRequest("أدخل الرمز للتبديل") { doSwitch(id) }
        else doSwitch(id)
    }

    fun openProfiles() { reloadProfileState(); screen = Screen.Profiles }

    fun addProfile(name: String, kids: Boolean) { store.addProfile(name, kids); profileList = store.profiles() }

    fun deleteProfile(id: String) {
        requirePin("أدخل الرمز السري") { store.deleteProfile(id); reloadProfileState() }
    }

    /* ───────── parental control ───────── */
    private fun guard(c: Channel, go: () -> Unit) {
        if (parental.needsPin(c)) pin = PinRequest("أدخل الرمز السري") { go() } else go()
    }

    fun submitPin(code: String): Boolean {
        val req = pin ?: return false
        if (!parental.unlock(code)) return false
        pin = null; bump(); req.onOk()
        return true
    }

    /** Runs [go] directly when no PIN is set, otherwise after the PIN is verified. */
    fun requirePin(title: String, go: () -> Unit) {
        if (!store.hasPin || parental.unlocked) go() else pin = PinRequest(title) { go() }
    }

    fun setPin(code: String) { store.setPin(code); bump() }
    fun clearPin() { requirePin("أدخل الرمز لإلغائه") { store.clearPin(); parental.lock(); bump() } }
    fun setLevel(m: Maturity) { requirePin("أدخل الرمز السري") { store.parentalLevel = m; bump() } }
    fun lockNow() { parental.lock(); bump() }

    fun toggleGroupLock(g: String) {
        if (!store.hasPin) { say("عيّن رمزاً سرياً أولاً من الإعدادات"); return }
        requirePin("أدخل الرمز السري") {
            store.lockedGroups = if (g in store.lockedGroups) store.lockedGroups - g else store.lockedGroups + g
            bump()
        }
    }

    fun togglePinGroup(g: String) {
        store.pinnedGroups = if (g in store.pinnedGroups) store.pinnedGroups - g else store.pinnedGroups + g; bump()
    }

    fun hideGroup(g: String) {
        requirePin("أدخل الرمز السري") { store.hiddenGroups = store.hiddenGroups + g; if (group == g) group = GROUP_ALL; bump() }
    }

    fun unhideAllGroups() { store.hiddenGroups = emptySet(); bump() }

    fun say(m: String) { message = m }

    /* ───────── favourites / custom groups / hiding ───────── */
    fun toggleFav(c: Channel) {
        favs = if (c.url in favs) favs - c.url else favs + c.url
        store.favorites = favs
        refreshRecs()
    }

    fun hideChannel(c: Channel) { store.hiddenChannels = store.hiddenChannels + c.url; bump(); say("تم إخفاء القناة") }
    fun unhideAllChannels() { store.hiddenChannels = emptySet(); bump() }

    fun addToCustomGroup(name: String, c: Channel) {
        val m = LinkedHashMap(store.customGroups())
        m[name] = (m[name] ?: emptyList()) + c.url
        store.saveCustomGroups(m); bump()
        say("أُضيفت إلى $name")
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

    fun openDetail(c: Channel) = guard(c) {
        val k = kindOf(c)
        detail = c; info = null; episodes = emptyList(); season = 1; error = null
        back = Screen.Main; screen = Screen.Detail
        viewModelScope.launch { info = repo.vodInfo(c, k) }
        if (k == Kind.Series) {
            epLoading = true
            viewModelScope.launch {
                try {
                    episodes = repo.loadEpisodes(c.url.removePrefix("series:").toInt())
                    season = episodes.firstOrNull()?.season ?: 1
                } catch (e: Exception) { error = friendly(e) }
                epLoading = false
            }
        }
    }

    fun closeDetail() { restoreFocus = true; screen = Screen.Main }
    fun selectSeason(n: Int) { season = n }

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
        if (limitReached()) { pin = PinRequest("انتهى وقت المشاهدة اليوم. أدخل الرمز السري للمتابعة") { }; return@guard }
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

    fun playEpisode(s: Channel, e: Episode) =
        play(Channel(-1, "${s.name} · م${e.season} ح${e.num}", e.url, s.logo, s.group), PlayMode.Vod)

    /** Episode that follows the one currently playing, if any (for auto-next). */
    fun nextEpisodeOf(c: Channel): Episode? {
        val i = episodes.indexOfFirst { it.url == c.url }
        return if (i >= 0) episodes.getOrNull(i + 1) else null
    }

    /** Episode before the current one, if any. */
    fun previousEpisodeOf(c: Channel): Episode? {
        val i = episodes.indexOfFirst { it.url == c.url }
        return if (i > 0) episodes.getOrNull(i - 1) else null
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
        if (u == null) { say("هذه القناة لا تدعم الإعادة"); return }
        play(Channel(-1, "${c.name} · ${p.title}", u, c.logo, c.group), PlayMode.Catchup, 0)
    }

    fun playRecording(r: Recording) {
        val f = r.file ?: return
        play(Channel(-1, r.title, "file://$f", null, r.channelName), PlayMode.Recording, 0)
    }

    fun stop() { sleepAt = 0L; history = store.history(); restoreFocus = true; screen = back; bump(); refreshRecs() }

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
        name + " — " + (if (r.state == Health.Dead) "ميت" else "بطيء") + " · " + r.failures + " فشل"
    }


    fun noteStart(c: Channel) {
        val old = store.historyOf(c.url)
        store.recordWatch(c, kindOf(c), old?.position ?: 0, old?.duration ?: 0, true)
    }

    fun saveProgress(c: Channel, pos: Long, dur: Long) {
        if (kindOf(c) == Kind.Live || playMode == PlayMode.Catchup) return
        val wasFinished = store.historyOf(c.url)?.finished == true
        store.recordWatch(c, kindOf(c), pos, dur, false)
        if (!wasFinished && store.historyOf(c.url)?.finished == true) traktMarkWatched(c)
        if (parental.visible(c)) store.historyOf(c.url)?.let { WatchNext.sync(ctx, it) }   // Android TV "Watch Next" row
        NabdWidget.refresh(ctx)   // phone home-screen widget
    }

    /* ───────── Trakt ───────── */
    private var traktJob: kotlinx.coroutines.Job? = null

    /** Starts the device-code link: shows the code on screen and waits for approval on trakt.tv/activate. */
    fun traktLink() {
        traktJob?.cancel()
        traktJob = viewModelScope.launch {
            try {
                val dc = Trakt.deviceCode(repo.http, store)
                menu = MenuRequest("افتح ${dc.url} وأدخل الكود: ${dc.userCode}", listOf("إلغاء" to { traktJob?.cancel() }))
                val ok = Trakt.awaitAuthorization(repo.http, store, dc)
                menu = null
                say(if (ok) "تم ربط حساب Trakt" else "لم يتم الربط (انتهت المهلة أو رُفض الطلب)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                menu = null; throw e
            } catch (e: Exception) { menu = null; say(e.message ?: "تعذّر الاتصال بـ Trakt") }
            bump()
        }
    }

    fun traktUnlink() { Trakt.unlink(store); say("تم فك ربط Trakt"); bump() }

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

    private fun playResume(url: String) {
        val h = store.historyOf(url) ?: return
        play(histChannel(h))
    }

    fun clearHistory() { store.clearHistory(); WatchNext.clearAll(ctx); history = emptyList(); recs = emptyList(); because = null; bump() }

    /* ───────── recording / reminders ───────── */
    fun record(c: Channel, p: Prog) {
        val clash = Recorder.conflicts(store, p.start, p.stop).isNotEmpty()
        val r = Recorder.schedule(ctx, store, c, p)
        recordings = store.recordings()
        say(when { r == null -> "مجدول مسبقاً"; clash -> "تمت الجدولة، لكنه يتعارض مع تسجيل آخر"; else -> "تمت جدولة التسجيل" })
    }

    fun autoRecord(c: Channel, p: Prog) {
        if (c.url !in favs) toggleFav(c)
        store.autoRecordTitles = store.autoRecordTitles + p.title.lowercase()
        Recorder.syncAuto(ctx, store, all, epg)
        recordings = store.recordings()
        say("سيتم تسجيل كل حلقات \"${p.title}\"")
    }

    fun remind(c: Channel, p: Prog) { Recorder.remind(ctx, store, c, p); say("سيصلك تنبيه قبل البدء") }

    fun cancelRecording(r: Recording) { Recorder.cancel(ctx, store, r.id); recordings = store.recordings() }
    fun deleteRecording(r: Recording) { Recorder.delete(ctx, store, r.id); recordings = store.recordings() }
    fun refreshRecordings() { recordings = store.recordings() }

    /* ───────── health ───────── */
    var healthStatus by mutableStateOf<String?>(null); private set

    /** Throttled probe of the favourites (or the current category, capped). */
    fun checkHealth(favoritesOnly: Boolean) {
        if (healthStatus != null) return
        val list = (if (favoritesOnly) liveList.filter { it.url in favs } else visible).take(150).map { it.url }
        if (list.isEmpty()) { say("لا توجد قنوات للفحص"); return }
        viewModelScope.launch {
            healthStatus = "0/${list.size}"
            val dead = health.check(list, 3) { d, t -> healthStatus = "$d/$t" }
            healthStatus = null; bump()
            say("اكتمل الفحص: $dead رابط معطّل من ${list.size}")
        }
    }

    fun clearHealth() { store.clearHealth(); bump() }

    /** (healthy, total) among the links that have actually been tested. */
    val healthSummary by derivedStateOf { rev; store.healthStats() }

    /* ───────── multi-view ───────── */
    var multi by mutableStateOf<List<Channel>>(emptyList()); private set

    fun toggleMulti(c: Channel) {
        multi = if (multi.any { it.url == c.url }) multi.filter { it.url != c.url }
        else if (multi.size >= 4) { say("الحد الأقصى 4 قنوات"); multi } else multi + c
        say("المشاهدة المتعددة: ${multi.size}/4")
    }

    fun openMulti() {
        if (multi.size < 2) { say("اختر قناتين على الأقل (ضغطة مطوّلة ← مشاهدة متعددة)"); return }
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
        items += (if (c.url in favs) "إزالة من المفضلة" else "إضافة للمفضلة") to { toggleFav(c) }
        if (k == Kind.Live) {
            items += "مشاهدة متعددة (${multi.size}/4)" to { toggleMulti(c) }
            items += "إضافة إلى مجموعة" to {
                val names = store.customGroups().keys.toList()
                menu = MenuRequest("المجموعات",
                    names.map { n -> n to { addToCustomGroup(n, c) } } +
                        ("+ مجموعة جديدة" to { prompt = PromptRequest("اسم المجموعة", "") { n -> if (n.isNotBlank()) addToCustomGroup(n.trim(), c) } })
                )
            }
            val alts = alternativesOf(c)
            if (alts.isNotEmpty()) items += "مصادر بديلة (${alts.size})" to {
                menu = MenuRequest("مصادر بديلة", alts.map { a -> (a.name + " · " + health.state(a.url).name) to { play(a) } })
            }
            items += "تعديل مطابقة الدليل" to {
                prompt = PromptRequest("معرّف القناة في الدليل (فارغ = تلقائي)", store.epgOverrides()[c.url] ?: c.tvgId ?: "") { id -> setEpgOverride(c, id.trim()) }
            }
        }
        items += "إخفاء" to { hideChannel(c) }
        menu = MenuRequest(c.name, items)
    }

    fun groupMenu(g: String) {
        if (g == GROUP_ALL || g == GROUP_FAV || g == GROUP_RECENT || g == GROUP_NEW) return
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (g.startsWith(GROUP_CUSTOM)) {
            items += "حذف المجموعة" to { removeCustomGroup(g.removePrefix(GROUP_CUSTOM)) }
        } else {
            items += (if (g in store.pinnedGroups) "إلغاء التثبيت" else "تثبيت في الأعلى") to { togglePinGroup(g) }
            items += (if (g in store.lockedGroups) "فك القفل" else "قفل بالرمز السري") to { toggleGroupLock(g) }
            items += "إخفاء التصنيف" to { hideGroup(g) }
        }
        menu = MenuRequest(groupLabel(g), items)
    }
}

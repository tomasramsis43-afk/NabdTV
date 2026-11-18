package com.nabd.tv

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

enum class Screen { Setup, Home, Player, SeriesDetail }
enum class Section { Live, Movies, Series }

const val GROUP_ALL = "الكل"
const val GROUP_FAV = "★ المفضلة"

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repo(app)

    var screen by mutableStateOf(if (repo.playlistUrl.isBlank()) Screen.Setup else Screen.Home)
        private set
    var all by mutableStateOf<List<Channel>>(emptyList())
        private set
    var movies by mutableStateOf<List<Channel>>(emptyList())
        private set
    var series by mutableStateOf<List<Channel>>(emptyList())
        private set
    var section by mutableStateOf(Section.Live)
        private set
    var group by mutableStateOf(GROUP_ALL)
    var query by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set
    var vodLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var favs by mutableStateOf(repo.favorites)
        private set
    var current by mutableStateOf<Channel?>(null)
        private set
    var zapEnabled by mutableStateOf(true)
        private set
    private var back = Screen.Home

    var openedSeries by mutableStateOf<Channel?>(null)
        private set
    var episodes by mutableStateOf<List<Episode>>(emptyList())
        private set
    var epLoading by mutableStateOf(false)
        private set
    var season by mutableStateOf(1)
        private set

    val savedUrl: String get() = repo.playlistUrl
    val hasXtream: Boolean get() = repo.hasXtream
    var epg by mutableStateOf<Map<String, List<Prog>>>(emptyMap())
        private set

    /** Current and next programme for a channel (null if no guide data). */
    fun nowNext(c: Channel): Pair<Prog?, Prog?> {
        val l = c.tvgId?.let { epg[it] } ?: return null to null
        val t = System.currentTimeMillis()
        val i = l.indexOfFirst { t >= it.start && t < it.stop }
        return if (i < 0) null to null else l[i] to l.getOrNull(i + 1)
    }

    private val base: List<Channel>
        get() = when (section) { Section.Live -> all; Section.Movies -> movies; Section.Series -> series }

    val groups by derivedStateOf { listOf(GROUP_ALL, GROUP_FAV) + base.map { it.group }.distinct().sorted() }
    val visible by derivedStateOf {
        base.filter {
            val g = when (group) { GROUP_ALL -> true; GROUP_FAV -> it.url in favs; else -> it.group == group }
            g && (query.isBlank() || it.name.contains(query, ignoreCase = true))
        }
    }

    init { if (repo.playlistUrl.isNotBlank()) load(false) }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "تعذّر الوصول للسيرفر (الاسم غير صحيح أو لا يوجد إنترنت)"
        is java.net.SocketTimeoutException -> "انتهت مهلة الاتصال بالسيرفر"
        is java.net.ConnectException -> "السيرفر رفض الاتصال، تأكد من العنوان والبورت"
        is IllegalArgumentException -> "العنوان غير صحيح: " + (e.message ?: "")
        else -> e.message ?: "خطأ غير معروف"
    }

    fun load(force: Boolean) {
        viewModelScope.launch {
            loading = true; error = null
            try {
                all = repo.load(force)
                group = GROUP_ALL
                screen = Screen.Home
                launch { epg = repo.loadEpg(all, force) }
            } catch (e: Exception) {
                error = friendly(e)
                if (all.isEmpty()) screen = Screen.Setup
            }
            loading = false
        }
    }

    private fun loadVod(s: Section, force: Boolean) {
        viewModelScope.launch {
            vodLoading = true; error = null
            try {
                if (s == Section.Movies) movies = repo.loadMovies(force) else series = repo.loadSeries(force)
            } catch (e: Exception) {
                error = friendly(e)
            }
            vodLoading = false
        }
    }

    fun select(s: Section) {
        section = s; group = GROUP_ALL; query = ""
        if (s == Section.Movies && movies.isEmpty()) loadVod(s, false)
        if (s == Section.Series && series.isEmpty()) loadVod(s, false)
    }

    fun refresh() { if (section == Section.Live) load(true) else loadVod(section, true) }

    private fun resetVod() {
        repo.clearVod(); movies = emptyList(); series = emptyList(); section = Section.Live
    }

    fun setUrl(url: String) {
        repo.epgManual = false
        repo.xtream = ""
        repo.playlistUrl = url.trim()
        resetVod()
        load(true)
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
        resetVod()
        load(true)
    }
    fun openSetup() { screen = Screen.Setup }
    fun closeSetup() { if (all.isNotEmpty()) screen = Screen.Home }

    fun openSeries(c: Channel) {
        openedSeries = c; episodes = emptyList(); season = 1; epLoading = true; error = null
        screen = Screen.SeriesDetail
        viewModelScope.launch {
            try {
                episodes = repo.loadEpisodes(c.url.removePrefix("series:").toInt())
                season = episodes.firstOrNull()?.season ?: 1
            } catch (e: Exception) {
                error = friendly(e)
            }
            epLoading = false
        }
    }
    fun closeSeries() { screen = Screen.Home }
    fun selectSeason(n: Int) { season = n }

    fun play(c: Channel) {
        back = screen
        zapEnabled = section == Section.Live && screen == Screen.Home
        current = c
        screen = Screen.Player
    }
    fun stop() { screen = back }

    fun zap(delta: Int) {
        if (!zapEnabled) return
        val list = visible
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.url == current?.url }
        current = list[((if (i < 0) 0 else i) + delta + list.size) % list.size]
    }

    fun toggleFav(c: Channel) {
        favs = if (c.url in favs) favs - c.url else favs + c.url
        repo.favorites = favs
    }
}

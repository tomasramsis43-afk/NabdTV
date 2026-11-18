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

enum class Screen { Setup, Home, Player }

const val GROUP_ALL = "الكل"
const val GROUP_FAV = "★ المفضلة"

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repo(app)

    var screen by mutableStateOf(if (repo.playlistUrl.isBlank()) Screen.Setup else Screen.Home)
        private set
    var all by mutableStateOf<List<Channel>>(emptyList())
        private set
    var group by mutableStateOf(GROUP_ALL)
    var query by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var favs by mutableStateOf(repo.favorites)
        private set
    var current by mutableStateOf<Channel?>(null)
        private set
    val savedUrl: String get() = repo.playlistUrl
    var epg by mutableStateOf<Map<String, List<Prog>>>(emptyMap())
        private set

    /** Current and next programme for a channel (null if no guide data). */
    fun nowNext(c: Channel): Pair<Prog?, Prog?> {
        val l = c.tvgId?.let { epg[it] } ?: return null to null
        val t = System.currentTimeMillis()
        val i = l.indexOfFirst { t >= it.start && t < it.stop }
        return if (i < 0) null to null else l[i] to l.getOrNull(i + 1)
    }

    val groups by derivedStateOf { listOf(GROUP_ALL, GROUP_FAV) + all.map { it.group }.distinct().sorted() }
    val visible by derivedStateOf {
        all.filter {
            val g = when (group) { GROUP_ALL -> true; GROUP_FAV -> it.url in favs; else -> it.group == group }
            g && (query.isBlank() || it.name.contains(query, ignoreCase = true))
        }
    }

    init { if (repo.playlistUrl.isNotBlank()) load(false) }

    fun load(force: Boolean) {
        viewModelScope.launch {
            loading = true; error = null
            try {
                all = repo.load(force)
                group = GROUP_ALL
                screen = Screen.Home
                launch { epg = repo.loadEpg(all, force) }
            } catch (e: Exception) {
                error = when (e) {
                    is java.net.UnknownHostException -> "تعذّر الوصول للسيرفر (الاسم غير صحيح أو لا يوجد إنترنت)"
                    is java.net.SocketTimeoutException -> "انتهت مهلة الاتصال بالسيرفر"
                    is java.net.ConnectException -> "السيرفر رفض الاتصال، تأكد من العنوان والبورت"
                    is IllegalArgumentException -> "العنوان غير صحيح: " + (e.message ?: "")
                    else -> e.message ?: "خطأ غير معروف"
                }
                if (all.isEmpty()) screen = Screen.Setup
            }
            loading = false
        }
    }

    fun setUrl(url: String) {
        repo.epgManual = false
        repo.xtream = ""
        repo.playlistUrl = url.trim()
        load(true)
    }

    /** Xtream Codes login → standard M3U + XMLTV endpoints. */
    fun setXtream(host: String, user: String, pass: String) {
        var base = host.trim().trimEnd('/')
        if (!base.startsWith("http")) base = "http://$base"
        val q = "username=${Uri.encode(user.trim())}&password=${Uri.encode(pass.trim())}"
        repo.epgManual = true
        repo.xtream = "$base\n${user.trim()}\n${pass.trim()}"
        repo.epgUrl = "$base/xmltv.php?$q"
        repo.playlistUrl = "$base/get.php?$q&type=m3u_plus&output=ts"
        load(true)
    }
    fun openSetup() { screen = Screen.Setup }
    fun closeSetup() { if (all.isNotEmpty()) screen = Screen.Home }

    fun play(c: Channel) { current = c; screen = Screen.Player }
    fun stop() { screen = Screen.Home }

    fun zap(delta: Int) {
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

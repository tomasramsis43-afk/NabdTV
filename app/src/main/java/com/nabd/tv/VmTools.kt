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
/* ───────── recording / reminders ───────── */
fun AppViewModel.record(c: Channel, p: Prog) {
    val clash = recCtl.schedule(c, p)
    say(recordMsg(clash != null, clash == true))
}

fun AppViewModel.autoRecord(c: Channel, p: Prog) {
    if (c.url !in favs) toggleFav(c)
    store.autoRecordTitles = store.autoRecordTitles + p.title.lowercase()
    Recorder.syncAuto(ctx, store, all, epg)
    recCtl.refresh()
    say(tx(R.string.rec_all_eps, p.title))
}

fun AppViewModel.remind(c: Channel, p: Prog) { Recorder.remind(ctx, store, c, p); say(tx(R.string.remind_set)) }

fun AppViewModel.cancelRecording(r: Recording) { recCtl.cancel(r) }

fun AppViewModel.deleteRecording(r: Recording) { recCtl.delete(r) }

fun AppViewModel.refreshRecordings() { recCtl.refresh() }

/** Throttled probe of the favourites (or the current category, capped). */
fun AppViewModel.checkHealth(favoritesOnly: Boolean) {
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

fun AppViewModel.clearHealth() { store.clearHealth(); bump() }

fun AppViewModel.toggleMulti(c: Channel) {
    multi = if (multi.any { it.url == c.url }) multi.filter { it.url != c.url }
    else if (multi.size >= 4) { say(tx(R.string.multi_max)); multi } else multi + c
    say(tx(R.string.multi_count, multi.size))
}

fun AppViewModel.openMulti() {
    if (multi.size < 2) { say(tx(R.string.multi_min)); return }
    back = Screen.Main; screen = Screen.MultiView
}

fun AppViewModel.closeMulti() { restoreFocus = true; screen = Screen.Main }

fun AppViewModel.clearMulti() { multi = emptyList() }

/* ───────── manual EPG match ───────── */
/** Loads the guide now (after switching it on in Settings). */
fun AppViewModel.reloadEpg() { viewModelScope.launch { epg = repo.loadEpg(all, false); bump() } }

fun AppViewModel.setEpgOverride(c: Channel, xmlId: String) {
    store.setEpgOverride(c.url, xmlId.ifBlank { null })
    viewModelScope.launch { epg = repo.loadEpg(all, false); bump() }
}

/* ───────── context menu for a channel / title ───────── */
fun AppViewModel.channelMenu(c: Channel) {
    val k = kindOf(c)
    val items = ArrayList<Pair<String, () -> Unit>>()
    if (k != Kind.Live) items += tx(R.string.details) to { openDetail(c) }
    items += (if (c.url in favs) tx(R.string.menu_fav_remove) else tx(R.string.menu_fav_add)) to { toggleFav(c) }
    if (k != Kind.Live) items += (if (c.url in laterUrls) tx(R.string.x_later_remove) else tx(R.string.x_later_add)) to { toggleLater(c) }
    if (k != Kind.Live && historyByUrl[c.url] != null) items += tx(R.string.menu_remove_history) to { removeFromHistory(c) }
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

fun AppViewModel.groupMenu(g: String) {
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

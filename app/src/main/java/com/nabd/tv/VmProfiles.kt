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
internal fun AppViewModel.reloadProfileState() {
    profile = store.currentProfile; profileList = store.profiles()
    favs = store.favorites; liked = store.liked; disliked = store.disliked; history = store.history()
    tab = runCatching { Tab.valueOf(store.startSection) }.getOrDefault(Tab.Home)
    group = GROUP_ALL; query = ""
    parental.lock(); bump(); refreshRecs()
}

internal fun AppViewModel.doSwitch(id: String) { store.switchProfile(id); reloadProfileState(); screen = Screen.Main }

/** Leaving a kids profile needs the PIN (when one is set), so a child cannot hop to an adult profile. */
fun AppViewModel.switchProfile(id: String) {
    if (id == profile.id) { screen = Screen.Main; return }
    if (profile.kids && store.hasPin && !parental.unlocked) pin = PinRequest(tx(R.string.pin_switch)) { doSwitch(id) }
    else doSwitch(id)
}

fun AppViewModel.openProfiles() { reloadProfileState(); screen = Screen.Profiles }

fun AppViewModel.addProfile(name: String, kids: Boolean) { store.addProfile(name, kids); profileList = store.profiles() }

fun AppViewModel.deleteProfile(id: String) {
    requirePin(tx(R.string.pin_enter)) { store.deleteProfile(id); reloadProfileState() }
}

/* ───────── parental control ───────── */
internal fun AppViewModel.guard(c: Channel, go: () -> Unit) {
    if (parental.needsPin(c)) pin = PinRequest(tx(R.string.pin_enter)) { go() } else go()
}

fun AppViewModel.submitPin(code: String): Boolean {
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
fun AppViewModel.requirePin(title: String, go: () -> Unit) {
    if (!store.hasPin || parental.unlocked) go() else pin = PinRequest(title) { go() }
}

fun AppViewModel.setPin(code: String) { store.setPin(code); bump() }

fun AppViewModel.clearPin() { requirePin(tx(R.string.pin_clear)) { store.clearPin(); parental.lock(); bump() } }

fun AppViewModel.setLevel(m: Maturity) { requirePin(tx(R.string.pin_enter)) { store.parentalLevel = m; bump() } }

fun AppViewModel.lockNow() { parental.lock(); bump() }

fun AppViewModel.toggleGroupLock(g: String) {
    if (!store.hasPin) { say(tx(R.string.pin_set_first)); return }
    requirePin(tx(R.string.pin_enter)) {
        store.lockedGroups = if (g in store.lockedGroups) store.lockedGroups - g else store.lockedGroups + g
        bump()
    }
}

fun AppViewModel.togglePinGroup(g: String) {
    store.pinnedGroups = if (g in store.pinnedGroups) store.pinnedGroups - g else store.pinnedGroups + g; bump()
}

fun AppViewModel.hideGroup(g: String) {
    requirePin(tx(R.string.pin_enter)) { store.hiddenGroups = store.hiddenGroups + g; if (group == g) group = GROUP_ALL; bump() }
}

fun AppViewModel.unhideAllGroups() { store.hiddenGroups = emptySet(); bump() }

fun AppViewModel.say(m: String) { message = m }

fun AppViewModel.toggleLater(c: Channel) {
    if (c.url in laterUrls) { store.removeWatchLater(c.url); say(tx(R.string.x_later_removed)) }
    else { store.addWatchLater(c.url); say(tx(R.string.x_later_added)) }
    laterUrls = store.watchLater
}

/** Leaves the list once the title has been started (series) or finished (movie). */
internal fun AppViewModel.dropLater(url: String) {
    if (url in laterUrls) { store.removeWatchLater(url); laterUrls = store.watchLater }
}

/** Thumbs up ([up] = true) or down. Pressing the same one again clears it. */
fun AppViewModel.rate(c: Channel, up: Boolean) {
    val had = if (up) c.url in liked else c.url in disliked
    liked = liked - c.url; disliked = disliked - c.url
    if (!had) { if (up) liked = liked + c.url else disliked = disliked + c.url }
    store.liked = liked; store.disliked = disliked
    refreshRecs()
    say(tx(if (had) R.string.rating_cleared_msg else if (up) R.string.rated_up_msg else R.string.rated_down_msg))
}

fun AppViewModel.toggleFav(c: Channel) {
    favs = if (c.url in favs) favs - c.url else favs + c.url
    store.favorites = favs
    refreshRecs()
}

fun AppViewModel.hideChannel(c: Channel) { store.hiddenChannels = store.hiddenChannels + c.url; bump(); say(tx(R.string.channel_hidden)) }

fun AppViewModel.unhideAllChannels() { store.hiddenChannels = emptySet(); bump() }

fun AppViewModel.addToCustomGroup(name: String, c: Channel) {
    val m = LinkedHashMap(store.customGroups())
    m[name] = (m[name] ?: emptyList()) + c.url
    store.saveCustomGroups(m); bump()
    say(tx(R.string.added_to, name))
}

fun AppViewModel.removeCustomGroup(name: String) {
    val m = LinkedHashMap(store.customGroups()); m.remove(name); store.saveCustomGroups(m)
    if (group == GROUP_CUSTOM + name) group = GROUP_ALL
    bump()
}

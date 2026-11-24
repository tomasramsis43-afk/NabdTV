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
fun AppViewModel.limitReached(): Boolean {
    return VmLogic.limitReached(store.limitMinutes, store.hasPin, parental.unlocked, store.watchedSeconds().toLong())
}

/** Called every 30 s while something is playing. */
fun AppViewModel.tickWatch(sec: Int) {
    store.addWatched(sec)
    if (limitReached() && screen == Screen.Player) {
        stop()
        pin = PinRequest(tx(R.string.pin_time_limit)) { }
    }
}

fun AppViewModel.checkUpdate(manual: Boolean) {
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

fun AppViewModel.installUpdate(auto: Boolean = false) {
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

fun AppViewModel.startPairing() {
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

fun AppViewModel.stopPairing() { pairing?.stop(); pairing = null; pairingUrl = null }

@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nabd.tv

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private data class SheetChoice(val title: String, val options: List<SheetOption>)

/** Lets a row report that it was clicked and take back focus when a choice list closes. */
private class RowFocus(val key: String?, val requester: FocusRequester, val onClicked: (String) -> Unit)
private val LocalRowFocus = compositionLocalOf<RowFocus?> { null }
private data class SheetOption(
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val action: () -> Unit
)

/** In-player settings: six tabs, every row performs a real action on the engine or on persistent settings. */
@Composable
fun PlayerSheet(
    ui: PlayerUiState,
    store: Store,
    vm: AppViewModel,
    actions: PlayerActions,
    player: ExoPlayer,
    ch: Channel,
    live: Boolean,
    introEnd: Long,
    canSaveIntro: Boolean,
    onClose: () -> Unit,
    onSaveIntro: () -> Unit,
    onClearIntro: () -> Unit,
    onRewindLive: (Int) -> Unit,
    onLiveEdge: () -> Unit,
    onAlternatives: () -> Unit
) {
    val ctx = LocalContext.current
    val settingsRev = ui.settingsRev
    if (settingsRev < -1) return   // keeps preference reads observable

    var tab by remember(ui.sheetRev) { mutableStateOf(ui.sheet ?: PlayerTab.Playback) }
    // Home = six big tiles (speed / subtitles / audio / picture / episodes / more); a tile opens a short list or its own page.
    var home by remember(ui.sheetRev) { mutableStateOf(ui.sheet == null || ui.sheet == PlayerTab.Playback) }
    var choice by remember { mutableStateOf<SheetChoice?>(null) }
    val scope = rememberCoroutineScope()
    val tabFocus = remember { FocusRequester() }
    val choiceFocus = remember { FocusRequester() }
    val restoreFocus = remember { FocusRequester() }
    var lastRow by remember { mutableStateOf<String?>(null) }
    var firstRun by remember { mutableStateOf(true) }

    // Focus is placed explicitly (retrying until it sticks): on open → the active tab; when a choice list opens →
    // the selected option; when it closes → the row that opened it. Without this the D-pad had no focus after the
    // list was swapped and could not move between cells.
    LaunchedEffect(choice) {
        if (firstRun) { firstRun = false; tabFocus.focusWhenReady() }
        else if (choice != null) choiceFocus.focusWhenReady()
        else if (lastRow != null) restoreFocus.focusWhenReady() else tabFocus.focusWhenReady()
    }

    // One Back press closes the whole sheet (it used to need two or three: list → tab → sheet).
    BackHandler { onClose() }

    fun openChoice(title: String, options: List<SheetOption>) {
        choice = SheetChoice(title, options)
    }

    /** Online subtitle search (OpenSubtitles): lists results, downloads the picked one and applies it. */
    fun searchOnlineSubtitles() {
        if (store.osKey.isBlank() || store.osUser.isBlank() || store.osPass.isBlank()) {
            vm.say(S(R.string.a_380a5c56)); return
        }
        val ep = vm.episodes.firstOrNull { it.url == ch.url }
        val title = OpenSubtitles.cleanQuery(if (ep != null) vm.detail?.name ?: ch.name else ch.name)
        if (title.isBlank()) { vm.say(S(R.string.a_5c745736)); return }
        vm.say(S(R.string.a_fd4fff03))
        scope.launch {
            try {
                val res = OpenSubtitles.search(vm.repo.http, store.osKey, title, ep?.season, ep?.num)
                if (res.isEmpty()) { vm.say(S(R.string.a_993db7fa)); return@launch }
                openChoice(S(R.string.a_c2e69bf5), res.map { r ->
                    SheetOption("[${r.lang}] ${r.label}  ·  ⬇ ${r.downloads}") {
                        vm.say(S(R.string.a_961f1e6d))
                        scope.launch {
                            try {
                                val sub = OpenSubtitles.download(vm.repo.http, ctx, store.osKey, store.osUser, store.osPass, r.fileId, ch.url, title)
                                actions.useSubtitle(sub)
                            } catch (e: Exception) { vm.say(e.message ?: S(R.string.a_c1eb83be)) }
                        }
                    }
                })
            } catch (e: Exception) { vm.say(e.message ?: S(R.string.a_bf1b9e82)) }
        }
    }

    fun speedChoice() = openChoice(S(R.string.a_6bc0cff3), PLAYER_SPEEDS.map { v ->
        SheetOption(if (v == 1f) S(R.string.a_ebca19a0) else "${v}x", v == ui.speed) { actions.setSpeed(v) }
    })

    fun subtitleChoice() {
        val dropExternal = { if (ui.extSub != null) actions.removeSubtitle() }
        openChoice(
            PlayerTab.Subtitles.label,
            listOf(SheetOption(S(R.string.a_43de6b2e), !ui.subOn) { actions.setSubtitlesEnabled(false) }) +
                ui.textTracks.mapIndexed { i, t -> SheetOption(t.label, t.selected && ui.subOn) { dropExternal(); actions.selectSubtitle(i) } } +
                listOf(
                    SheetOption(S(R.string.a_61151639)) { actions.loadSubtitle() },
                    SheetOption(S(R.string.a_29531391), enabled = !live) { searchOnlineSubtitles() },
                    SheetOption(S(R.string.player_more)) { tab = PlayerTab.Subtitles; home = false }
                )
        )
    }

    fun audioChoice() = openChoice(
        S(R.string.tab_subs_audio),
        listOf(SheetOption(S(R.string.a_4cdfdadb)) { actions.clearAudioOverride() }) +
            ui.audioTracks.map { t -> SheetOption(t.label, t.selected) { actions.setAudioTrack(t) } } +
            listOf(SheetOption(S(R.string.a_ba704975), store.audioBoost) { actions.toggleAudioBoost() })
    )

    fun setOrientation(pref: OrientationPref) {
        store.playerOrientation = pref.name
        val act = ctx.findActivity()
        if (act != null && !isTelevision(ctx)) {
            act.requestedOrientation = when (pref) {
                OrientationPref.Auto -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                OrientationPref.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                OrientationPref.Portrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
        actions.changed()
    }

    Box(
        // pointerInput (not clickable): a clickable scrim is focusable and trapped the D-pad on a full-screen node.
        Modifier.fillMaxSize().background(Color(0xB3000000)).pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.94f)
                .widthIn(max = 400.dp)
                .background(Color(0xF0101420), RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                .border(1.dp, OverlayLine, RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                .pointerInput(Unit) { detectTapGestures { } }   // taps inside never reach the scrim
                .focusProperties { exit = { FocusRequester.Cancel } }   // remote focus cannot leave the sheet
                .focusGroup()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerRoundButton("✕", size = 38) { onClose() }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(S(R.string.a_30c78ed3), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                    Text(ch.name, fontSize = 12.sp, color = TextSecondary, maxLines = 1)
                }
                if (ui.stats) PlayerRoundButton("📊", size = 38, active = true) { ui.stats = false }
            }

            Spacer(Modifier.height(14.dp))
            if (!home) {
                PlayerButton("←  " + (if (tab == PlayerTab.Subtitles || tab == PlayerTab.Audio) S(R.string.tab_subs_audio) else tab.label), active = true, focusRequester = tabFocus, onClick = { home = true; choice = null })
            }
            Spacer(Modifier.height(10.dp))

            if (choice != null) {
                val c = choice!!
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlayerRoundButton("←", size = 36) { choice = null }
                        Spacer(Modifier.width(10.dp))
                        Text(c.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Accent, maxLines = 1)
                    }
                    Spacer(Modifier.height(10.dp))
                    val target = c.options.indexOfFirst { it.selected && it.enabled }.takeIf { it >= 0 }
                        ?: c.options.indexOfFirst { it.enabled }.coerceAtLeast(0)
                    c.options.forEachIndexed { i, o ->
                        SheetRow(
                            o.label, if (o.selected) "●" else "", enabled = o.enabled,
                            focusRequester = if (i == target) choiceFocus else null, rememberClick = false
                        ) {
                            o.action()
                            choice = null
                        }
                        Spacer(Modifier.height(5.dp))
                    }
                }
            } else if (home) {
                val hasEpisodes = !live && vm.episodes.any { it.url == ch.url }
                val subValue = if (!ui.subOn) S(R.string.a_43de6b2e) else ui.textTracks.firstOrNull { it.selected }?.label ?: ui.extSub?.name ?: "—"
                val audioValue = (ui.audioTracks.firstOrNull { it.selected } ?: ui.audioTracks.firstOrNull())?.label ?: "—"
                val items = buildList<Triple<String, String, Pair<String, () -> Unit>>> {
                    add(Triple("⏱", S(R.string.a_1bcf7e91), (if (ui.speed == 1f) "1x" else "${ui.speed}x") to { speedChoice() }))
                    add(Triple("CC", PlayerTab.Subtitles.label, subValue to { subtitleChoice() }))
                    add(Triple("♪", S(R.string.tab_subs_audio), audioValue to { audioChoice() }))
                    add(Triple("▭", PlayerTab.Display.label, ui.aspect.label.substringBefore(' ') to { tab = PlayerTab.Display; home = false }))
                    if (hasEpisodes) add(Triple("☰", PlayerTab.Episodes.label, "" to { tab = PlayerTab.Episodes; home = false }))
                    add(Triple("⋯", S(R.string.player_more), "" to { tab = PlayerTab.Playback; home = false }))
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items.chunked(2).forEachIndexed { r, pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEachIndexed { c, (ic, label, vl) ->
                                SheetTile(ic, label, vl.first, Modifier.weight(1f), if (r == 0 && c == 0) tabFocus else null, vl.second)
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            } else {
                CompositionLocalProvider(LocalRowFocus provides RowFocus(lastRow, restoreFocus) { lastRow = it }) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(end = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    when (tab) {
                        PlayerTab.Episodes -> {
                            // Netflix / Kodi style episode list: ▶ playing · ✓ watched · % in progress.
                            val eps = vm.episodes
                            val seasons = eps.map { it.season }.distinct().sorted()
                            var sSel by remember(ch.url) { mutableStateOf(eps.firstOrNull { it.url == ch.url }?.season ?: seasons.firstOrNull() ?: 1) }
                            if (seasons.size > 1) {
                                SheetRow(S(R.string.a_68474436), S(R.string.detail_season, sSel)) {
                                    openChoice(S(R.string.a_3a0a6b8a), seasons.map { n -> SheetOption(S(R.string.detail_season, n), n == sSel) { sSel = n } })
                                }
                            }
                            SheetSection(S(R.string.x_season_eps, sSel))
                            eps.filter { it.season == sSel }.forEach { e ->
                                val h = store.historyOf(e.url)
                                val playingNow = e.url == ch.url
                                val value = when {
                                    playingNow -> S(R.string.a_a55f903d)
                                    h?.finished == true -> S(R.string.a_ccb8e152)
                                    h != null && h.progress > 0.02f -> "${(h.progress * 100).toInt()}%"
                                    else -> ""
                                }
                                val t = e.title.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
                                SheetRow(S(R.string.ep_default_title, e.num) + t, value, selected = playingNow) {
                                    val d = vm.detail
                                    if (!playingNow && d != null) { vm.playEpisode(d, e); onClose() }
                                }
                            }
                        }
                        PlayerTab.Playback -> {
                            SheetSection(S(R.string.a_73113273))
                            SheetRow(S(R.string.a_1bcf7e91), if (ui.speed == 1f) S(R.string.a_e7331441) else "${ui.speed}x", detail = S(R.string.a_2dc3fc6b)) {
                                openChoice(S(R.string.a_6bc0cff3), PLAYER_SPEEDS.map { v ->
                                    SheetOption(if (v == 1f) S(R.string.a_ebca19a0) else "${v}x", v == ui.speed) { actions.setSpeed(v) }
                                })
                            }
                            SheetRow(S(R.string.a_3c6411ea), fmtTime(ui.position)) { ui.showJump = true; onClose() }
                            if (!live && canSaveIntro) {   // next-episode behaviour only matters for series
                            SheetSection(S(R.string.a_79e08935))
                            SheetRow(S(R.string.a_954384b8), if (store.autoNext) S(R.string.a_a41566d2) else S(R.string.a_bafc4458)) {
                                store.autoNext = !store.autoNext; actions.changed()
                            }
                            }
                            if (!live) {   // automatic skipping applies to movies and episodes alike
                                SheetRow(S(R.string.auto_skip_intro), if (store.autoSkip) S(R.string.a_a41566d2) else S(R.string.a_bafc4458)) {
                                    store.autoSkip = !store.autoSkip; actions.changed()
                                }
                                SheetRow(S(R.string.auto_skip_credits), if (store.autoSkipCredits) S(R.string.a_a41566d2) else S(R.string.a_bafc4458)) {
                                    store.autoSkipCredits = !store.autoSkipCredits; actions.changed()
                                }
                            }
                            // Rarely used options stay folded so the menu is short; one row opens them.
                            var more by remember { mutableStateOf(false) }
                            SheetRow(S(R.string.player_more), if (more) "▲" else "▼") { more = !more }
                            if (more) {
                                if (LocalDevice.current.touch) SheetRow(PlayerTab.Gestures.label) { tab = PlayerTab.Gestures; ui.sheet = PlayerTab.Gestures }
                                SheetRow(PlayerTab.Info.label) { tab = PlayerTab.Info; ui.sheet = PlayerTab.Info }
                            SheetRow(S(R.string.a_608dc8b8), S(R.string.x_seconds, store.seekInterval)) {
                                openChoice(S(R.string.a_6d0922bc), SEEK_INTERVALS.map { v ->
                                    SheetOption(S(R.string.x_seconds, v), v == store.seekInterval) {
                                        store.seekInterval = v
                                        ui.seekInterval = v
                                        actions.changed()
                                    }
                                })
                            }
                            SheetRow(S(R.string.a_59fd59df)) { actions.restart(); onClose() }
                            SheetRow(S(R.string.a_896dada2), if (ui.loop) S(R.string.a_a41566d2) else S(R.string.a_bafc4458)) { actions.setLoop(!ui.loop) }

                            SheetRow(S(R.string.a_fab55504), ResumeMode.of(store.resumeMode).label) {
                                openChoice(S(R.string.a_fab55504), ResumeMode.entries.map { m ->
                                    SheetOption(m.label, m.name == store.resumeMode) {
                                        store.resumeMode = m.name; actions.changed()
                                    }
                                })
                            }
                            val sleepLabel = when {
                                ui.sleepEnd -> S(R.string.a_db02b041)
                                vm.sleepAt > 0 -> S(R.string.x_min_left, ((vm.sleepAt - System.currentTimeMillis()).coerceAtLeast(0) / 60000L) + 1)
                                else -> S(R.string.a_bafc4458)
                            }
                            SheetRow(S(R.string.a_9fa87337), sleepLabel) {
                                openChoice(S(R.string.a_558d3a2f), listOf(15, 30, 45, 60, 90, 120).map { m ->
                                    SheetOption(S(R.string.x_minutes, m)) { actions.setSleep(m) }
                                } + listOf(
                                    SheetOption(S(R.string.a_29efb0ed)) { actions.setSleepEnd() },
                                    SheetOption(S(R.string.a_2b71fd45)) { actions.cancelSleep() }
                                ))
                            }

                            }

                            if (live) {
                                SheetSection(S(R.string.a_ac845696))
                                SheetRow(S(R.string.a_2327fe5f)) { onLiveEdge(); onClose() }
                                SheetRow(S(R.string.a_2630c6fd)) { actions.openChannels(); onClose() }
                                SheetRow(S(R.string.a_e2f83e37), if (ch.url in vm.favs) S(R.string.a_08f544ec) else S(R.string.a_5e3a3fdf)) { vm.toggleFav(ch) }
                                SheetRow(S(R.string.a_e33cb38d), "${vm.alternativesOf(ch).size}") { onAlternatives(); onClose() }
                                if (!LocalDevice.current.touch) SheetRow(S(R.string.a_776ca067), UpDownAction.of(store.tvUpDown).label) {
                                    openChoice(S(R.string.a_776ca067), UpDownAction.entries.map { a ->
                                        SheetOption(a.label, a.name == store.tvUpDown) { store.tvUpDown = a.name; actions.changed() }
                                    })
                                }
                            } else if (canSaveIntro) {
                                SheetSection(S(R.string.a_ab8a01be))
                                SheetRow(S(R.string.a_021a0268), if (introEnd > 0) fmtTime(introEnd) else "—") { onSaveIntro() }
                                SheetRow(S(R.string.a_f9d5783a), enabled = introEnd > 0) { onClearIntro() }
                            }
                        }

                        PlayerTab.Audio -> {
                            SheetSection(S(R.string.a_beb4436d))
                            SheetRow(S(R.string.a_ba704975), if (store.audioBoost) S(R.string.a_a41566d2) else S(R.string.a_bafc4458)) { actions.toggleAudioBoost() }
                            SheetRow(S(R.string.a_e9952d72), S(R.string.x_tracks_n, ui.audioTracks.size), detail = ui.audioTracks.firstOrNull()?.label.orEmpty()) {
                                val items = listOf(SheetOption(S(R.string.a_4cdfdadb)) { actions.clearAudioOverride() }) +
                                    ui.audioTracks.mapIndexed { i, t ->
                                        SheetOption(t.label, t.selected) { actions.setAudioTrack(t) }
                                    }
                                if (items.size == 1) vm.say(S(R.string.a_b767d344)) else openChoice(S(R.string.a_e9952d72), items)
                            }
                        }

                        PlayerTab.Subtitles -> {
                            SheetSection(S(R.string.a_4a47a85a))
                            SheetRow(S(R.string.a_dd37ba8a), if (ui.subOn) S(R.string.a_a41566d2) else S(R.string.a_bafc4458)) { actions.setSubtitlesEnabled(!ui.subOn) }
                            SheetRow(S(R.string.a_c04d9164), S(R.string.x_tracks_n, ui.textTracks.size)) {
                                // The external file is dropped only when an embedded track is actually chosen, not when the list opens.
                                val dropExternal = { if (ui.extSub != null) actions.removeSubtitle() }
                                val items = listOf(SheetOption(S(R.string.a_43de6b2e)) { actions.setSubtitlesEnabled(false) }) +
                                    ui.textTracks.mapIndexed { i, t ->
                                        SheetOption(t.label, t.selected) { dropExternal(); actions.selectSubtitle(i) }
                                    }
                                if (items.size == 1) vm.say(S(R.string.a_4b48bb9d)) else openChoice(S(R.string.a_5acd5081), items)
                            }

                            SheetRow(S(R.string.a_29531391), "OpenSubtitles", enabled = !live) { searchOnlineSubtitles() }
                            SheetSection(S(R.string.a_beb4436d))
                            SheetRow(S(R.string.a_ba704975), if (store.audioBoost) S(R.string.a_a41566d2) else S(R.string.a_bafc4458)) { actions.toggleAudioBoost() }
                            SheetRow(S(R.string.a_e9952d72), S(R.string.x_tracks_n, ui.audioTracks.size), detail = ui.audioTracks.firstOrNull()?.label.orEmpty()) {
                                val items = listOf(SheetOption(S(R.string.a_4cdfdadb)) { actions.clearAudioOverride() }) +
                                    ui.audioTracks.mapIndexed { i, t ->
                                        SheetOption(t.label, t.selected) { actions.setAudioTrack(t) }
                                    }
                                if (items.size == 1) vm.say(S(R.string.a_b767d344)) else openChoice(S(R.string.a_e9952d72), items)
                            }
                            // Everything else is folded: one row opens it.
                            var moreSub by remember { mutableStateOf(false) }
                            SheetRow(S(R.string.player_more), if (moreSub) "▲" else "▼") { moreSub = !moreSub }
                            if (moreSub) {
                            SheetRow(S(R.string.a_61151639), ui.extSub?.name ?: S(R.string.a_d5e96ebf)) { actions.loadSubtitle() }
                            SheetRow(S(R.string.a_04cd1f90), enabled = ui.extSub != null) { actions.removeSubtitle() }
                            ui.extError?.let { SheetInfo(S(R.string.detail_error, it), danger = true) }
                            SheetRow(S(R.string.a_80dd0013), "${store.subDelayMs} ms", enabled = ui.extSub != null) {
                                openChoice(S(R.string.a_80dd0013), listOf(-1000, -500, -250, -100, -50, 0, 50, 100, 250, 500, 1000).map { v ->
                                    SheetOption(
                                        when {
                                            v < 0 -> S(R.string.x_adv, -v)
                                            v > 0 -> S(R.string.x_delay, v)
                                            else -> S(R.string.a_5d9f1039)
                                        },
                                        v == store.subDelayMs
                                    ) { actions.setSubtitleDelay(v) }
                                })
                            }
                            SheetRow(S(R.string.a_ba44c449), if (store.subEncoding == "auto") S(R.string.a_bbf6d1eb) else store.subEncoding, enabled = ui.extSub != null) {
                                openChoice(S(R.string.a_2701fb15), SubtitleParser.ENCODINGS.map { e ->
                                    SheetOption(if (e == "auto") S(R.string.a_f8616546) else e, e == store.subEncoding) {
                                        actions.setSubtitleEncoding(e)
                                    }
                                })
                            }

                            SheetSection(S(R.string.a_583f0b73))
                            SheetRow(S(R.string.a_be45eacd), "${(store.subSize * 100).roundToInt()}%") {
                                openChoice(S(R.string.a_1acfde50), (5..14).map { v ->
                                    SheetOption("$v%", v == (store.subSize * 100).roundToInt()) {
                                        store.subSize = v / 100f; actions.changed()
                                    }
                                })
                            }
                            SheetRow(S(R.string.a_96ad3946), "${(store.subPosition * 100).roundToInt()}%") {
                                openChoice(S(R.string.a_b8ffdfa3), listOf(4, 8, 12, 18, 24, 32, 42).map { v ->
                                    SheetOption("$v%", v == (store.subPosition * 100).roundToInt()) {
                                        store.subPosition = v / 100f; actions.changed()
                                    }
                                })
                            }
                            SheetRow(S(R.string.a_bd59217a), SUBTITLE_COLORS.firstOrNull { it.second == store.subColor }?.first ?: S(R.string.a_b5b126bd)) {
                                openChoice(S(R.string.a_32a44b18), SUBTITLE_COLORS.map { (name, argb) ->
                                    SheetOption(name, argb == store.subColor) { store.subColor = argb; actions.changed() }
                                })
                            }
                            }
                        }
                        PlayerTab.Display -> {
                            SheetSection(S(R.string.a_80e8bb66))
                            SheetRow(S(R.string.a_06160737), ui.aspect.label) {
                                openChoice(S(R.string.a_06160737), AspectMode.entries.map { a ->
                                    SheetOption(a.label, a == ui.aspect) { actions.setAspect(a) }
                                })
                            }
                            SheetRow(S(R.string.a_fa908227), "${(ui.zoom * 100).roundToInt()}%") {
                                openChoice(S(R.string.a_4882b5dd), listOf(100, 125, 150, 200, 250, 300, 400).map { v ->
                                    SheetOption("$v%", v == (ui.zoom * 100).roundToInt()) {
                                        actions.resetZoom(); actions.zoomBy(v / 100f - 1f)
                                    }
                                })
                            }
                            SheetRow(S(R.string.a_a696bee7)) { actions.resetTransform() }
                            SheetRow(S(R.string.a_7fba6576), OrientationPref.of(store.playerOrientation).label) {
                                openChoice(S(R.string.a_7fba6576), OrientationPref.entries.map { p ->
                                    SheetOption(p.label, p.name == store.playerOrientation) { setOrientation(p) }
                                })
                            }
                        }

                        PlayerTab.Gestures -> {
                            SheetSection(S(R.string.a_70cb8e8b))
                            SheetRow(S(R.string.a_c19bc5bf), if (store.gesturesEnabled) S(R.string.a_a41566d2) else S(R.string.a_bafc4458)) {
                                store.gesturesEnabled = !store.gesturesEnabled; actions.changed()
                            }
                            SheetRow(S(R.string.a_7041d2b3), onOff(store.gestureSeek)) {
                                store.gestureSeek = !store.gestureSeek; actions.changed()
                            }
                            SheetRow(S(R.string.a_55b21bbc), onOff(store.gestureBrightness)) {
                                store.gestureBrightness = !store.gestureBrightness; actions.changed()
                            }
                            SheetRow(S(R.string.a_02fd9a6f), onOff(store.gestureVolume)) {
                                store.gestureVolume = !store.gestureVolume; actions.changed()
                            }
                            SheetRow(S(R.string.a_689528ae), onOff(store.gestureDoubleTap)) {
                                store.gestureDoubleTap = !store.gestureDoubleTap; actions.changed()
                            }
                        }

                        PlayerTab.Info -> {
                            SheetSection(S(R.string.a_8f13bd83))
                            Box(
                                Modifier.fillMaxWidth().background(Color(0x99101520), RoundedCornerShape(12.dp)).padding(12.dp)
                            ) {
                                Text(ui.infoText.ifBlank { S(R.string.a_b7cd9fda) }, fontSize = 12.sp, color = Color(0xFFC9D3E4))
                            }
                            Spacer(Modifier.height(6.dp))
                            SheetRow(S(R.string.a_e4aa6ef3), if (ui.stats) S(R.string.a_a41566d2) else S(R.string.a_bafc4458)) { ui.stats = !ui.stats }
                            SheetSection(S(R.string.a_18afa9a3))
                            SheetRow(S(R.string.a_2d31f780), BufferProfile.of(store.bufferProfile).label, detail = BufferProfile.of(store.bufferProfile).detail) {
                                openChoice(S(R.string.a_2d31f780), BufferProfile.entries.map { p ->
                                    SheetOption(p.label, p.name == store.bufferProfile) {
                                        store.bufferProfile = p.name
                                        actions.changed()
                                        actions.recreatePlayer()
                                    }
                                })
                            }
                            SheetRow(S(R.string.a_5cfcffb9), DecoderMode.of(store.decoderMode).label, detail = S(R.string.a_285ed640)) {
                                openChoice(S(R.string.a_e032dd98), DecoderMode.entries.map { m ->
                                    SheetOption(m.label, m.name == store.decoderMode) {
                                        store.decoderMode = m.name
                                        actions.changed()
                                        actions.recreatePlayer()
                                    }
                                })
                            }
                            SheetRow(S(R.string.a_2e47b5f2)) { actions.retry(); onClose() }

                            SheetRow(S(R.string.a_134bdb7d)) { actions.enterPip(); onClose() }
                            SheetRow(S(R.string.a_9859e227)) { actions.openExternal(); onClose() }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                }
            }
        }
    }
}

private fun onOff(on: Boolean): String = if (on) S(R.string.a_a41566d2) else S(R.string.a_bafc4458)

@Composable
private fun SheetSection(title: String) {
    Text(
        title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Accent,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp), maxLines = 1
    )
}

@Composable
private fun SheetInfo(text: String, danger: Boolean = false) {
    Text(
        text, fontSize = 12.sp, color = if (danger) Color(0xFFFF9A9A) else TextSecondary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun SheetTile(icon: String, label: String, value: String, modifier: Modifier, focusRequester: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.heightIn(min = 96.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) Color(0xFF2A3355) else Color(0x12FFFFFF))
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(icon, fontSize = 24.sp, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        if (value.isNotBlank()) Text(value, fontSize = 12.sp, color = Accent, maxLines = 1)
    }
}

@Composable
private fun SheetRow(
    label: String,
    value: String = "",
    detail: String? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    rememberClick: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val rf = LocalRowFocus.current
    val fr = focusRequester ?: if (rememberClick && rf != null && rf.key == label) rf.requester else null
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .then(if (fr != null) Modifier.focusRequester(fr) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    selected -> AccentFill
                    focused -> Color(0xFF2A3355)
                    else -> Color.Transparent
                }
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier.drawBehind { drawLine(Color(0x1FFFFFFF), Offset(12.dp.toPx(), size.height), Offset(size.width - 12.dp.toPx(), size.height), 1.dp.toPx()) })
            .clickable(enabled = enabled) { if (rememberClick) rf?.onClicked(label); onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label, fontSize = 14.sp, maxLines = 1,
                fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    !enabled -> Color(0x66FFFFFF)
                    selected -> OnAccent
                    else -> Color.White
                }
            )
            if (!detail.isNullOrBlank()) Text(detail, fontSize = 12.sp, color = TextSecondary, maxLines = 2)
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                value, fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.Bold,
                color = if (selected) OnAccent else Accent
            )
        }
    }
}

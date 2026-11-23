package com.nabd.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlin.math.roundToInt

/** Which small menu is open above the control bar. */
enum class PopupKind { Settings, Subs, Episodes }

private class PopItem(
    val label: String,
    val value: String = "",
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val opensSub: Boolean = false,   // true: stays open and shows a sub-list; false: applies and closes
    val action: () -> Unit
)

/**
 * Small menu that pops up right above the button that opened it (⋮ settings, CC subtitles, ☰ episodes):
 * a handful of rows instead of the full-height settings panel. "More…" opens the full panel for the rare options.
 */
@Composable
fun BoxScope.PlayerPopup(
    kind: PopupKind,
    sub: String?,
    onSub: (String?) -> Unit,
    onClose: () -> Unit,
    onMore: (PlayerTab) -> Unit,
    hasEpisodes: Boolean,
    ui: PlayerUiState,
    store: Store,
    vm: AppViewModel,
    actions: PlayerActions,
    ch: Channel
) {
    val dev = LocalDevice.current
    val pad = if (dev.tv) 48.dp else 14.dp
    val btn = (if (dev.tv) 48.dp else 42.dp) + 4.dp
    val slot = when (kind) { PopupKind.Settings -> 0; PopupKind.Episodes -> 1; PopupKind.Subs -> if (hasEpisodes) 2 else 1 }

    BackHandler { if (sub != null && kind == PopupKind.Settings) onSub(null) else onClose() }

    val s = S(R.string.tab_subs_audio)
    val subLabel = PlayerTab.Subtitles.label
    val offLabel = S(R.string.a_43de6b2e)

    fun speedItems() = PLAYER_SPEEDS.map { v ->
        PopItem(if (v == 1f) S(R.string.a_ebca19a0) else "${v}x", selected = v == ui.speed) { actions.setSpeed(v) }
    }
    fun subItems() = listOf(PopItem(offLabel, selected = !ui.subOn) { actions.setSubtitlesEnabled(false) }) +
        ui.textTracks.mapIndexed { i, t ->
            PopItem(t.label, selected = t.selected && ui.subOn) { if (ui.extSub != null) actions.removeSubtitle(); actions.selectSubtitle(i) }
        } + listOf(
            PopItem(S(R.string.a_61151639), opensSub = true) { actions.loadSubtitle(); onClose() },
            PopItem(S(R.string.player_more), opensSub = true) { onMore(PlayerTab.Subtitles) }
        )
    fun audioItems() = listOf(PopItem(S(R.string.a_4cdfdadb)) { actions.clearAudioOverride() }) +
        ui.audioTracks.map { t -> PopItem(t.label, selected = t.selected) { actions.setAudioTrack(t) } } +
        listOf(PopItem(S(R.string.a_ba704975), selected = store.audioBoost) { actions.toggleAudioBoost() })
    fun pictureItems() = AspectMode.entries.map { a -> PopItem(a.label, selected = a == ui.aspect) { actions.setAspect(a) } }

    val eps = vm.episodes
    val seasons = eps.map { it.season }.distinct().sorted()
    var season by remember(ch.url) { mutableStateOf(eps.firstOrNull { it.url == ch.url }?.season ?: seasons.firstOrNull() ?: 1) }

    // title + items for what is showing now
    val title: String?
    val items: List<PopItem>
    when {
        kind == PopupKind.Subs -> { title = null; items = subItems() }
        kind == PopupKind.Episodes && sub == "seasons" -> {
            title = S(R.string.a_68474436)
            items = seasons.map { n -> PopItem(S(R.string.detail_season, n), selected = n == season) { season = n; onSub(null) } }
        }
        kind == PopupKind.Episodes -> {
            title = null
            val head = if (seasons.size > 1) listOf(PopItem(S(R.string.detail_season, season), "▾", opensSub = true) { onSub("seasons") }) else emptyList()
            items = head + eps.filter { it.season == season }.map { e ->
                val h = store.historyOf(e.url)
                val now = e.url == ch.url
                val v = when {
                    now -> "▶"
                    h?.finished == true -> "✓"
                    h != null && h.progress > 0.02f -> "${(h.progress * 100).toInt()}%"
                    else -> ""
                }
                PopItem(S(R.string.ep_default_title, e.num), v, selected = now) {
                    val d = vm.detail
                    if (!now && d != null) vm.playEpisode(d, e)
                }
            }
        }
        sub == "speed" -> { title = S(R.string.a_1bcf7e91); items = speedItems() }
        sub == "subs" -> { title = subLabel; items = subItems() }
        sub == "audio" -> { title = s; items = audioItems() }
        sub == "picture" -> { title = PlayerTab.Display.label; items = pictureItems() }
        else -> {
            title = null
            val subVal = if (!ui.subOn) offLabel else ui.textTracks.firstOrNull { it.selected }?.label ?: ui.extSub?.name ?: "—"
            val audVal = (ui.audioTracks.firstOrNull { it.selected } ?: ui.audioTracks.firstOrNull())?.label ?: "—"
            items = listOf(
                PopItem(S(R.string.a_1bcf7e91), if (ui.speed == 1f) "1x" else "${ui.speed}x", opensSub = true) { onSub("speed") },
                PopItem(subLabel, subVal, opensSub = true) { onSub("subs") },
                PopItem(s, audVal, opensSub = true) { onSub("audio") },
                PopItem(PlayerTab.Display.label, ui.aspect.label.substringBefore(' '), opensSub = true) { onSub("picture") },
                PopItem(S(R.string.player_more), opensSub = true) { onMore(PlayerTab.Playback) }
            )
        }
    }

    // Tapping outside closes the menu.
    Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { onClose() } })

    val first = remember(kind, sub) { FocusRequester() }
    LaunchedEffect(kind, sub) { first.focusWhenReady() }
    LaunchedEffect(kind, sub) { actions.interact() }

    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.align(Alignment.BottomEnd)
            .padding(end = pad + btn * slot, bottom = if (dev.tv) 150.dp else 112.dp)
            .width(if (dev.tv) 260.dp else 230.dp)
            .heightIn(max = (dev.heightDp - 190).coerceAtLeast(140).dp)
            .clip(shape).background(Color(0xF2121420)).border(1.dp, Color(0x22FFFFFF), shape)
            .pointerInput(Unit) { detectTapGestures { } }
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(6.dp)
    ) {
        if (title != null) {
            PopRow("‹  $title", "", false, true, null) { onSub(null) }
        }
        val target = items.indexOfFirst { it.selected }.takeIf { it >= 0 } ?: 0
        items.forEachIndexed { i, it ->
            PopRow(it.label, it.value, it.selected, it.enabled, if (i == target && title == null || (title != null && i == target)) first else null) {
                it.action()
                actions.interact()
                if (!it.opensSub) onClose()
            }
        }
    }
}

@Composable
private fun PopRow(label: String, value: String, selected: Boolean, enabled: Boolean, focus: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 42.dp)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) Color.White else if (selected) Color(0x24FFFFFF) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f),
            fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
            color = if (focused) Color(0xFF101522) else if (!enabled) Color(0x66FFFFFF) else Color.White
        )
        val v = if (selected && value.isEmpty()) "✓" else value
        if (v.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(v, fontSize = 12.sp, maxLines = 1, color = if (focused) Color(0xFF3A4A8A) else Accent)
        }
    }
}

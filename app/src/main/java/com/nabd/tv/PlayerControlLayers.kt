@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nabd.tv

import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage

/** Top bar, movie/series controls, live controls and the bottom bar over the video. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun BoxScope.PlayerControlLayers(
    ui: PlayerUiState,
    store: Store,
    vm: AppViewModel,
    actions: PlayerActions,
    player: ExoPlayer,
    chRef: State<Channel>,
    live: Boolean,
    vod: Boolean,
    dev: Device,
    seriesKey: String?,
    am: AudioManager,
    thumbnails: ThumbnailLoader?,
    timelineFocus: FocusRequester,
    playFocus: FocusRequester,
    popupState: MutableState<PopupKind?>,
    popupSubState: MutableState<String?>,
    openSheet: (PlayerTab) -> Unit,
) {
    val ch = chRef.value
    var popup by popupState
    var popupSub by popupSubState
    // Top bar
    if (ui.overlay && !ui.locked && !vm.inPip && ui.sheet == null && !vod && !live) {
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(OverlayScrimStrong, Color.Transparent)))
                .padding(
                    horizontal = if (dev.tv) 48.dp else 12.dp,
                    vertical = if (dev.tv) 22.dp else 8.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerRoundButton("✕", size = 42) { vm.stop() }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val name = if (seriesKey != null) (vm.detail?.name ?: ch.name) + " — " + ch.name else ch.name
                Text(name, fontSize = if (dev.tv) 20.sp else 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                Text(
                    if (live) ch.group else (player.videoFormat?.let { "${it.width}×${it.height}" } ?: fmtTime(ui.duration)),
                    fontSize = 12.sp, color = Accent, maxLines = 1
                )
            }
            if (live) {
                Text(S(R.string.a_76ad5744), fontSize = 12.sp, color = Color(0xFFFF8A8F),
                    modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(8.dp)).padding(8.dp))
                Spacer(Modifier.width(8.dp))
            }
            PlayerRoundButton("📊", size = 40, active = ui.stats) { ui.stats = !ui.stats }
            Spacer(Modifier.width(6.dp))
            if (dev.touch) {
                PlayerRoundButton("🔒", size = 40) { actions.toggleLock() }
                Spacer(Modifier.width(6.dp))
            }
            PlayerRoundButton("⛶", size = 40) { actions.enterPip() }
            Spacer(Modifier.width(6.dp))
            PlayerRoundButton("⚙", size = 40) { openSheet(PlayerTab.Playback) }
        }
    }

    // Movies / series: the new icon-based controls.
    if (vod && ui.overlay && !ui.locked && !vm.inPip && ui.sheet == null && ui.error == null && ui.resumeAt <= 0L &&
        !ui.showJump && ui.nextEp == null && !ui.ended
    ) {
        VodOverlay(
            title = if (seriesKey != null) {
                // Series: only the season and episode number, never the series name.
                vm.episodes.firstOrNull { it.url == chRef.value.url }?.let { S(R.string.player_season_ep, it.season, it.num) } ?: ch.name
            } else ch.name,
            subtitle = player.videoFormat?.let { "${it.width}×${it.height}" },
            ui = ui, thumbnails = thumbnails, seekInterval = ui.seekInterval,
            hasPrev = vm.hasPreviousEpisode(chRef.value), hasNext = vm.hasNextEpisode(chRef.value),
            hasEpisodes = vm.episodes.any { it.url == chRef.value.url },
            timelineFocus = timelineFocus,
            onBack = { vm.stop() }, onToggle = { actions.togglePlay() },
            onSeekBy = actions.seekBy, onSeekTo = actions.seekTo,
            onPrev = { actions.prevEpisode() }, onNext = { actions.nextEpisode() },
            onSubs = { popupSub = null; popup = if (popup == PopupKind.Subs) null else PopupKind.Subs },
            onEpisodes = { popupSub = null; popup = if (popup == PopupKind.Episodes) null else PopupKind.Episodes },
            onSettings = { popupSub = null; popup = if (popup == PopupKind.Settings) null else PopupKind.Settings },
            onPip = { actions.enterPip() }, onLock = { actions.toggleLock() },
            onInteract = actions.interact
        )
        popup?.let { k ->
            PlayerPopup(
                kind = k, sub = popupSub, onSub = { popupSub = it }, onClose = { popup = null; popupSub = null },
                onMore = { tab -> popup = null; popupSub = null; openSheet(tab) },
                hasEpisodes = vm.episodes.any { it.url == chRef.value.url },
                ui = ui, store = store, vm = vm, actions = actions, ch = chRef.value
            )
        }
    }

    // Movies / series: time left (counting down) while the controls are hidden.
    if (vod && !ui.overlay && !vm.inPip && ui.sheet == null && ui.error == null && ui.resumeAt <= 0L &&
        !ui.showJump && ui.nextEp == null && !ui.ended && ui.duration > 0L
    ) {
        VodMiniBar(ui.position, ui.duration, dev.tv)
    }

    // Live TV: same icon-based controls as movies and series.
    if (live && ui.overlay && !ui.locked && !vm.inPip && ui.sheet == null && ui.error == null && ui.resumeAt <= 0L) {
        val (now, next) = vm.nowNext(chRef.value)
        LiveOverlay(
            title = (if (chRef.value.url in vm.favs) "★ " else "") + chRef.value.name,
            group = chRef.value.group,
            logo = chRef.value.logo,
            nowTitle = now?.title,
            nowProgress = now?.let { ((System.currentTimeMillis() - it.start).toFloat() / (it.stop - it.start).coerceAtLeast(1)).coerceIn(0f, 1f) },
            timeLine = now?.let { "${hm(it.start)} - ${hm(it.stop)}" + (next?.let { n -> " · " + S(R.string.x_next_prog, n.title) } ?: "") },
            behindLive = player.isCurrentMediaItemLive && player.currentLiveOffset > 8_000L,
            ui = ui, thumbnails = thumbnails, seekInterval = ui.seekInterval,
            timelineFocus = timelineFocus, playFocus = playFocus,
            onBack = { vm.stop() }, onToggle = { actions.togglePlay() },
            onSeekBy = actions.seekBy, onSeekTo = actions.seekTo,
            onRewind = { rewindLive(vm, player, chRef.value.url, ui.seekInterval); ui.ping++ },
            onLiveEdge = { player.seekToDefaultPosition(); ui.ping++ },
            onChannels = { actions.openChannels() },
            onSubs = { openSheet(PlayerTab.Subtitles) },
            onSettings = { openSheet(PlayerTab.Playback) },
            onPip = { actions.enterPip() }, onLock = { actions.toggleLock() },
            onInteract = actions.interact
        )
    }

    // Bottom control bar (catch-up, recordings)
    if (ui.overlay && !ui.locked && !vm.inPip && ui.sheet == null && ui.error == null && ui.resumeAt <= 0L && !vod && !live) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000))))
                .padding(horizontal = if (dev.tv) 48.dp else 12.dp, vertical = if (dev.tv) 20.dp else 10.dp)
        ) {
            if (live) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = ch.logo, contentDescription = null, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text((if (ch.url in vm.favs) "★ " else "") + ch.name, fontSize = 17.sp, color = Color.White, maxLines = 1)
                        val (now, next) = vm.nowNext(ch)
                        Text(now?.title ?: ch.group, fontSize = 12.sp, color = Accent, maxLines = 1)
                        if (now != null) {
                            val pct = ((System.currentTimeMillis() - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                            Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(3.dp).background(Color(0x33FFFFFF))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(pct).background(Accent))
                            }
                            Text(
                                "${hm(now.start)} - ${hm(now.stop)}" + (next?.let { " · " + S(R.string.x_next_prog, it.title) } ?: ""),
                                fontSize = 12.sp, color = OverlayText, maxLines = 1
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (ui.duration > 0) {
                PlayerTimeline(
                    ui = ui,
                    seekInterval = ui.seekInterval,
                    thumbnails = thumbnails,
                    onSeekBy = actions.seekBy,
                    onSeekTo = actions.seekTo,
                    onInteract = actions.interact
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (live) {
                    PlayerButton(S(R.string.a_97646aab)) { actions.openChannels() }
                    PlayerButton("⏪ 30") { rewindLive(vm, player, chRef.value.url, 30); ui.ping++ }
                    if (player.isCurrentMediaItemLive && player.currentLiveOffset > 8_000L) {
                        PlayerButton(S(R.string.a_76ad5744)) { player.seekToDefaultPosition(); ui.ping++ }
                    }
                } else {
                    PlayerButton("⏮", enabled = vm.hasPreviousEpisode(chRef.value)) { actions.prevEpisode() }
                }
                PlayerButton("⏪ ${ui.seekInterval}") { actions.seekBy(-ui.seekInterval * 1_000L) }
                PlayerRoundButton(
                    if (ui.playing) "⏸" else "▶", size = 54, focusRequester = playFocus
                ) { actions.togglePlay() }
                PlayerButton("${ui.seekInterval} ⏩") { actions.seekBy(ui.seekInterval * 1_000L) }
                if (!live) {
                    PlayerButton("⏭", enabled = vm.hasNextEpisode(chRef.value)) { actions.nextEpisode() }
                }
                PlayerButton(if (am.isStreamMute(AudioManager.STREAM_MUSIC)) "🔇" else "🔊") { actions.toggleMute() }
                if (!live && vm.episodes.any { it.url == chRef.value.url }) PlayerButton(S(R.string.a_88259e6d)) { openSheet(PlayerTab.Episodes) }
                PlayerButton(S(R.string.a_b75445ac), active = ui.subOn) { openSheet(PlayerTab.Subtitles) }
                PlayerButton(S(R.string.a_1b377a5e), active = store.audioBoost) { openSheet(PlayerTab.Audio) }
                PlayerButton(if (ui.speed == 1f) S(R.string.a_3f4a4d97) else "⏱ ${ui.speed}x", active = ui.speed != 1f) { openSheet(PlayerTab.Playback) }
                PlayerButton("⛶ ${ui.aspect.label.substringBefore(' ')}") { openSheet(PlayerTab.Display) }
                PlayerButton("🔁", active = ui.loop) { actions.setLoop(!ui.loop) }
                if (dev.touch) PlayerButton(S(R.string.a_b82b4db1)) { actions.toggleLock() }
                PlayerButton(S(R.string.a_c01ec84a)) { openSheet(PlayerTab.Playback) }
                PlayerButton("📊") { ui.stats = !ui.stats }
            }
        }
    }
}

/** Only the time left, counting down, in the bottom corner when the full controls are hidden (no bar, no label). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BoxScope.VodMiniBar(position: Long, duration: Long, tv: Boolean) {
    Text(
        fmtTime((duration - position).coerceAtLeast(0L)),
        color = Color(0xE6FFFFFF), fontSize = if (tv) 18.sp else 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = if (tv) 48.dp else 14.dp, bottom = if (tv) 18.dp else 10.dp)
            .clip(RoundedCornerShape(8.dp)).background(Color(0x73000000)).padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

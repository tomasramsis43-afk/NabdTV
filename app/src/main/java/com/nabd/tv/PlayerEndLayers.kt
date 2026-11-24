@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nabd.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/** Cards drawn over the video: next episode, end of playback, resume, error, time jump. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun BoxScope.PlayerEndLayers(
    ui: PlayerUiState,
    store: Store,
    vm: AppViewModel,
    actions: PlayerActions,
    player: ExoPlayer,
    chRef: State<Channel>,
    nextFocus: FocusRequester,
    endFocus: FocusRequester,
) {
    ui.nextEp?.let { ep ->
        Column(
            Modifier.align(Alignment.BottomEnd).padding(24.dp)
                .background(Color(0xEE101522), RoundedCornerShape(16.dp))
                .border(1.dp, OverlayLine, RoundedCornerShape(16.dp)).padding(16.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                if (store.autoNext && !ui.sleepEnd) S(R.string.x_next_ep_5, ep.num)
                else S(R.string.x_next_ep, ep.num),
                color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MBtn(S(R.string.a_501e36f0), modifier = Modifier.focusRequester(nextFocus)) { ui.nextEp = null; vm.playNext() }
                MBtn(S(R.string.a_e776b020), filled = false) { ui.nextEp = null }
            }
        }
    }

    if (ui.ended && !vm.inPip) {
        Column(
            Modifier.align(Alignment.Center).background(Color(0xEE101522), RoundedCornerShape(18.dp))
                .border(1.dp, OverlayLine, RoundedCornerShape(18.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(S(R.string.a_05fc16d5), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MBtn(S(R.string.a_ba89cfd4), modifier = Modifier.focusRequester(endFocus)) { actions.restart() }
                if (vm.hasNextEpisode(chRef.value)) MBtn(S(R.string.a_938cb270)) { vm.playNext() }
                MBtn(S(R.string.a_a8bdc009), filled = false) { vm.stop() }
            }
        }
    }

    if (ui.resumeAt > 0 && !vm.inPip) {
        ResumePrompt(
            position = ui.resumeAt,
            onResume = {
                val target = ui.resumeAt
                ui.resumeAt = -1L
                player.seekTo(target)
                player.playWhenReady = true
            },
            onRestart = {
                ui.resumeAt = -1L
                player.seekTo(0L)
                player.playWhenReady = true
            }
        )
    }

    ui.error?.let { err ->
        PlayerErrorCard(
            message = err,
            detail = ui.errorDetail,
            onRetry = actions.retry,
            onExternal = actions.openExternal,
            onExit = { vm.stop() }
        )
    }

    if (ui.showJump && !vm.inPip) {
        TimeJumpDialog(
            current = ui.position,
            duration = ui.duration,
            onJump = actions.jumpTo,
            onDismiss = { ui.showJump = false }
        )
    }
}

/** Hosts the player's settings sheet. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PlayerSheetHost(
    ui: PlayerUiState,
    store: Store,
    vm: AppViewModel,
    actions: PlayerActions,
    player: ExoPlayer,
    chRef: State<Channel>,
    live: Boolean,
    introEnd: Long,
    seriesKey: String?,
    rootFocus: FocusRequester,
) {
    ui.sheet?.let { tab ->
        key(ui.sheetRev) {
            PlayerSheet(
                ui = ui,
                store = store,
                vm = vm,
                actions = actions,
                player = player,
                ch = chRef.value,
                live = live,
                introEnd = introEnd,
                canSaveIntro = seriesKey != null,
                onClose = { ui.sheet = null; ui.sheetRev++; runCatching { rootFocus.requestFocus() } },
                onSaveIntro = {
                    seriesKey?.let { k ->
                        store.setIntroEnd(k, player.currentPosition)
                        vm.say(S(R.string.a_8b16f482))
                    }
                },
                onClearIntro = { seriesKey?.let { vm.store.setIntroEnd(it, 0L); vm.say(S(R.string.a_3ede4634)) } },
                onRewindLive = { sec -> rewindLive(vm, player, chRef.value.url, sec) },
                onLiveEdge = { player.seekToDefaultPosition(); player.playWhenReady = true; ui.ping++ },
                onAlternatives = {
                    val alts = vm.alternativesOf(chRef.value)
                    if (alts.isEmpty()) vm.say(S(R.string.a_174d0794))
                    else vm.menu = com.nabd.tv.MenuRequest(
                        S(R.string.a_e33cb38d),
                        alts.map { a -> (a.name + " · " + vm.health.state(a.url).name) to { vm.useAlternative(a) } }
                    )
                }
            )
        }
    }
}

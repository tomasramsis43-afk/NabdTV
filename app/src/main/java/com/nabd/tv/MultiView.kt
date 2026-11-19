package com.nabd.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/** 2–4 live channels at once. Only the selected tile has sound; OK on a tile = give it the sound. */
@OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)
@Composable
fun MultiViewScreen(vm: AppViewModel) {
    KeepScreenOn()
    val ctx = LocalContext.current
    val channels = vm.multi
    val players = remember(channels.map { it.url }) {
        channels.map { c ->
            // Tiny buffers + no decoder extensions keep 4 simultaneous streams light on cheap TV boxes.
            buildPlayer(ctx, vm.store, true).apply {
                setMediaItem(MediaItem.fromUri(c.url)); prepare(); playWhenReady = true; volume = 0f
            }
        }
    }
    var audio by remember { mutableIntStateOf(0) }
    LaunchedEffect(audio, players) { players.forEachIndexed { i, p -> p.volume = if (i == audio) 1f else 0f } }
    DisposableEffect(players) { onDispose { players.forEach(ExoPlayer::release) } }
    BackHandler { vm.closeMulti() }

    val cols = if (channels.size <= 2) 1 else 2
    val rows = if (channels.size <= 2) channels.size else 2
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        for (r in 0 until rows) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (cIdx in 0 until (if (cols == 1) 1 else 2)) {
                    val i = r * cols + cIdx
                    if (i >= players.size) { Spacer(Modifier.weight(1f)); continue }
                    var focused by remember { mutableStateOf(false) }
                    Box(
                        Modifier.weight(1f).fillMaxHeight().padding(2.dp)
                            .border(if (i == audio) 3.dp else if (focused) 2.dp else 0.dp, if (i == audio) Accent else Color.White)
                            .onFocusChanged { focused = it.isFocused }
                            .clickable { audio = i }
                            .focusable()
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { c -> PlayerView(c).apply { useController = false; player = players[i]; isFocusable = false } }
                        )
                        Text(
                            (if (i == audio) "🔊 " else "") + channels[i].name, fontSize = 12.sp, color = Color.White,
                            modifier = Modifier.align(Alignment.TopStart).background(Color(0x99000000)).padding(6.dp)
                        )
                    }
                }
            }
        }
    }
}

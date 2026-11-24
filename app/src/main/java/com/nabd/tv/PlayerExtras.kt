@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nabd.tv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.tv.material3.Text
import coil.compose.AsyncImage

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * Smart aspect: fill the screen only when that crops little (<= 15 %), otherwise keep the full picture.
 * Avoids both permanent black bars on near-matching screens and cut-off heads on mismatched ones.
 */
fun smartMode(videoAr: Float, w: Int, h: Int): Int {
    if (videoAr <= 0f || w <= 0 || h <= 0) return AspectRatioFrameLayout.RESIZE_MODE_FIT
    val s = w.toFloat() / h
    val crop = 1f - minOf(videoAr, s) / maxOf(videoAr, s)
    return if (crop <= 0.15f) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
}

/** Pending "go back N seconds" request that is applied once an HLS (seekable) stream is ready. */
object LiveRewind { @Volatile var pending = 0 }

/**
 * Live rewind (time-shift). HLS streams expose a DVR window we can seek in; plain .ts streams cannot,
 * so for those we reopen the same channel as .m3u8 and apply the rewind when it is ready.
 */
fun rewindLive(vm: AppViewModel, player: ExoPlayer, url: String, sec: Int) {
    if (player.isCurrentMediaItemSeekable) {
        player.seekTo(maxOf(0L, player.currentPosition - sec * 1000L))
        vm.say(S(R.string.x_player_back, sec))
    } else if (url.endsWith(".ts")) {
        LiveRewind.pending = sec
        player.setMediaItem(MediaItem.fromUri(url.removeSuffix(".ts") + ".m3u8"))
        player.prepare(); player.playWhenReady = true
        vm.say(S(R.string.a_2bbc14f2))
    } else vm.say(S(R.string.a_32fe8f74))
}

    .pointerInput(key) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { o -> if (!live) onSeek(if (o.x < size.width / 2f) -1 else 1) },
            onLongPress = { onLongPress() }
        )
    }
    .pointerInput(key) {
        var zone = 0
        var acc = 0f
        detectVerticalDragGestures(
            onDragStart = { o ->
                acc = 0f
                zone = when { o.x < size.width * 0.28f -> 1; o.x > size.width * 0.72f -> 2; else -> 0 }
            },
            onDragEnd = { if (zone == 0) { if (acc < -120f) onZap(1) else if (acc > 120f) onZap(-1) } }
        ) { _, d ->
            acc += d
            if (zone != 0) onSide(zone == 1, -d / size.height)
        }
    }
    .pointerInput(key) {
        // Swipe left/right anywhere on the video = scrub (VOD only), like MX Player / VLC.
        var acc = 0f
        detectHorizontalDragGestures(
            onDragStart = { acc = 0f; if (!live) onHDragStart() },
            onDragEnd = { if (!live) onHDragEnd() },
            onDragCancel = { if (!live) onHDragEnd() }
        ) { _, d ->
            if (!live) { acc += d / size.width; onHDrag(acc) }
        }
    }

/** Channel list that slides over the video, so you can switch channels without leaving the stream. */
@Composable
fun ChannelPanel(vm: AppViewModel, current: Channel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val list = vm.zapList
    val idx = list.indexOfFirst { it.url == current.url }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, idx - 2))
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.focusWhenReady() }
    val dev = LocalDevice.current
    Column(
        modifier.fillMaxWidth(0.5f).widthIn(max = 320.dp).fillMaxHeight().background(Color(0xEE0B0F14))
            .padding(horizontal = if (dev.tv) 36.dp else 12.dp, vertical = if (dev.tv) 27.dp else 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(S(R.string.x_channels_title, list.size), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.weight(1f))
            Text("✕", color = TextSecondary, fontSize = 18.sp, modifier = Modifier.clickable { onClose() }.padding(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list.size) { i ->
                val c = list[i]
                val sel = c.url == current.url
                Focusable(
                    selected = sel, onClick = { vm.switchTo(c) }, zoom = 1f,
                    modifier = Modifier.fillMaxWidth().then(if (sel) Modifier.focusRequester(fr) else Modifier)
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
                                color = if (sel) Accent else Color.White,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                            )
                            vm.nowNext(c).first?.let {
                                Text(it.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = TextSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

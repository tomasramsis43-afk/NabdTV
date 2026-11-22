package com.nabd.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale as dsScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlin.math.cos
import kotlin.math.sin

fun fmtTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/** Flat line icons for the movie / series player (24-unit grid, round strokes). */
enum class VIc { Play, Pause, Prev, Next, Back10, Fwd10, Subs, Episodes, Settings, Back, Lock, Pip, Channels }

private fun DrawScope.drawVIc(ic: VIc, c: Color, rtl: Boolean) {
    val u = size.minDimension / 24f
    val st = Stroke(1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun tri(a: Offset, b: Offset, d: Offset) = drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(d.x, d.y); close() }, c)
    fun bar(x: Float, y: Float, w: Float, h: Float) =
        drawRoundRect(c, p(x, y), Size(w * u, h * u), CornerRadius(1.2f * u))
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, p(x1, y1), p(x2, y2), st.width, StrokeCap.Round)
    when (ic) {
        VIc.Play -> tri(p(8f, 5f), p(19f, 12f), p(8f, 19f))
        VIc.Pause -> { bar(6.5f, 5f, 3.6f, 14f); bar(13.9f, 5f, 3.6f, 14f) }
        VIc.Next -> { tri(p(6f, 5.5f), p(15f, 12f), p(6f, 18.5f)); bar(16.8f, 5.5f, 2.6f, 13f) }
        VIc.Prev -> { tri(p(18f, 5.5f), p(9f, 12f), p(18f, 18.5f)); bar(4.6f, 5.5f, 2.6f, 13f) }
        VIc.Fwd10, VIc.Back10 -> {
            // Plain double chevron: the skip step is configurable, so no fixed number is drawn.
            val mirror = ic == VIc.Back10
            dsScale(if (mirror) -1f else 1f, 1f, pivot = Offset(size.width / 2, size.height / 2)) {
                drawPath(Path().apply { moveTo(6.5f * u, 6f * u); lineTo(12.5f * u, 12f * u); lineTo(6.5f * u, 18f * u) }, c, style = st)
                drawPath(Path().apply { moveTo(12f * u, 6f * u); lineTo(18f * u, 12f * u); lineTo(12f * u, 18f * u) }, c, style = st)
            }
        }
        VIc.Subs -> {
            drawRoundRect(c, p(3f, 5f), Size(18f * u, 14f * u), CornerRadius(3.2f * u), style = st)
            line(6.5f, 10.5f, 10.5f, 10.5f); line(13f, 10.5f, 17.5f, 10.5f)
            line(6.5f, 14f, 12f, 14f); line(14.5f, 14f, 17.5f, 14f)
        }
        VIc.Episodes -> {
            for (y in listOf(7f, 12f, 17f)) {
                drawCircle(c, 1.2f * u, p(5f, y)); line(9f, y, 20f, y)
            }
        }
        VIc.Settings -> { for (y in listOf(5.5f, 12f, 18.5f)) drawCircle(c, 1.9f * u, p(12f, y)) }
        VIc.Back -> {
            val x = { v: Float -> if (rtl) 24f - v else v }
            drawPath(Path().apply { moveTo(x(15f) * u, 5f * u); lineTo(x(8f) * u, 12f * u); lineTo(x(15f) * u, 19f * u) }, c, style = st)
        }
        VIc.Lock -> {
            drawRoundRect(c, p(6f, 11f), Size(12f * u, 9f * u), CornerRadius(2.4f * u))
            drawArc(c, 180f, 180f, false, p(8.6f, 4.5f), Size(6.8f * u, 6.8f * u), style = st)
            line(8.6f, 8f, 8.6f, 11f); line(15.4f, 8f, 15.4f, 11f)
        }
        VIc.Channels -> {
            drawRoundRect(c, p(3f, 6f), Size(18f * u, 12f * u), CornerRadius(2.6f * u), style = st)
            line(9f, 21f, 15f, 21f); line(12f, 18f, 12f, 21f)
        }
        VIc.Pip -> {
            drawRoundRect(c, p(3f, 5f), Size(18f * u, 14f * u), CornerRadius(3f * u), style = st)
            drawRoundRect(c, p(12f, 11.5f), Size(6.5f * u, 4.5f * u), CornerRadius(1.2f * u))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VIcon(ic: VIc, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawVIc(ic, tint, rtl) }
    }
}

/** Round glass button; the focused one turns solid white and grows, so the remote position is never in doubt. */
@Composable
fun VodBtn(
    ic: VIc, modifier: Modifier = Modifier, size: Dp = 48.dp, primary: Boolean = false, enabled: Boolean = true,
    focusRequester: FocusRequester? = null, onFocus: () -> Unit = {}, onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (focused) 1.14f else 1f, tween(130), label = "vbtn")
    val bg = when {
        focused -> Color.White
        primary -> Color(0x33FFFFFF)
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> Color(0x55FFFFFF)
        focused -> Color(0xFF101522)
        else -> Color.White
    }
    Box(
        modifier.size(size).scale(sc)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .clip(CircleShape).background(bg)
            .then(if (focused) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { VIcon(ic, fg, size * 0.5f) }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CaptionBtn(ic: VIc, label: String, size: Dp, onFocus: () -> Unit, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        VodBtn(ic, size = size, onFocus = onFocus, onClick = onClick)
        Text(label, fontSize = 12.sp, color = Color(0xCCFFFFFF), maxLines = 1)
    }
}

/**
 * Movie / series controls: title on top; timeline and one short row of icon buttons at the bottom.
 * Any remote key shows it; focus starts on the timeline so Left/Right scrub straight away.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BoxScope.VodOverlay(
    title: String, subtitle: String?, ui: PlayerUiState, thumbnails: ThumbnailLoader?, seekInterval: Int,
    hasPrev: Boolean, hasNext: Boolean, hasEpisodes: Boolean,
    timelineFocus: FocusRequester,
    onBack: () -> Unit, onToggle: () -> Unit, onSeekBy: (Long) -> Unit, onSeekTo: (Long) -> Unit,
    onPrev: () -> Unit, onNext: () -> Unit, onSubs: () -> Unit, onEpisodes: () -> Unit,
    onSettings: () -> Unit, onPip: () -> Unit, onLock: () -> Unit, onInteract: () -> Unit
) {
    val dev = LocalDevice.current
    val narrow = dev.widthDp < 600
    val side = if (dev.tv) 48.dp else 42.dp
    val big = if (dev.tv) 56.dp else 48.dp
    val pad = if (dev.tv) 48.dp else 14.dp

    // Top: just the back button (and PiP / lock on touch); the picture stays clean.
    Row(
        Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0x80000000), Color.Transparent)))
            .padding(horizontal = pad, vertical = if (dev.tv) 18.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        VodBtn(VIc.Back, size = 44.dp, onFocus = onInteract, onClick = onBack)
        Spacer(Modifier.weight(1f))
        if (dev.touch) {
            VodBtn(VIc.Pip, size = 44.dp, onFocus = onInteract, onClick = onPip)
            VodBtn(VIc.Lock, size = 44.dp, onFocus = onInteract, onClick = onLock)
        }
    }

    // Bottom
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(horizontal = pad, vertical = if (dev.tv) 20.dp else 10.dp)
    ) {
        Text(title, fontSize = if (dev.tv) 20.sp else 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 11.sp, color = Color(0x99FFFFFF), maxLines = 1)
        Spacer(Modifier.height(6.dp))
        if (ui.duration > 0) {
            PlayerTimeline(
                ui = ui, seekInterval = seekInterval, thumbnails = thumbnails,
                onSeekBy = onSeekBy, onSeekTo = onSeekTo, onInteract = onInteract,
                modifier = Modifier, focusRequester = timelineFocus, onToggle = onToggle
            )
            Spacer(Modifier.height(4.dp))
        }
        val transport: @Composable () -> Unit = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(horizontalArrangement = Arrangement.spacedBy(if (dev.tv) 14.dp else if (narrow) 2.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hasPrev) VodBtn(VIc.Prev, size = side, onFocus = onInteract, onClick = onPrev)
                    VodBtn(VIc.Back10, size = side, onFocus = onInteract) { onSeekBy(-seekInterval * 1_000L) }
                    VodBtn(if (ui.wantPlay) VIc.Pause else VIc.Play, size = big, primary = true, onFocus = onInteract, onClick = onToggle)
                    VodBtn(VIc.Fwd10, size = side, onFocus = onInteract) { onSeekBy(seekInterval * 1_000L) }
                    if (hasNext) VodBtn(VIc.Next, size = side, onFocus = onInteract, onClick = onNext)
                }
            }
        }
        val options: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                VodBtn(VIc.Subs, size = side, onFocus = onInteract, onClick = onSubs)
                if (hasEpisodes) VodBtn(VIc.Episodes, size = side, onFocus = onInteract, onClick = onEpisodes)
                VodBtn(VIc.Settings, size = side, onFocus = onInteract, onClick = onSettings)
            }
        }
        // One bar: playback controls on the start side, options on the other.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            transport()
            Spacer(Modifier.weight(1f))
            options()
        }
    }
}


/**
 * Live TV controls in the same look as [VodOverlay]: channel + now/next on top, one short row of
 * icon buttons at the bottom. The timeline appears only when the stream has a seekable window.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BoxScope.LiveOverlay(
    title: String, group: String, logo: String?, nowTitle: String?, nowProgress: Float?, timeLine: String?,
    behindLive: Boolean, ui: PlayerUiState, thumbnails: ThumbnailLoader?, seekInterval: Int,
    timelineFocus: FocusRequester, playFocus: FocusRequester,
    onBack: () -> Unit, onToggle: () -> Unit, onSeekBy: (Long) -> Unit, onSeekTo: (Long) -> Unit,
    onRewind: () -> Unit, onLiveEdge: () -> Unit, onChannels: () -> Unit, onSubs: () -> Unit,
    onSettings: () -> Unit, onPip: () -> Unit, onLock: () -> Unit, onInteract: () -> Unit
) {
    val dev = LocalDevice.current
    val narrow = dev.widthDp < 600
    val side = if (dev.tv) 56.dp else 48.dp
    val big = if (dev.tv) 72.dp else 62.dp
    val pad = if (dev.tv) 48.dp else 14.dp

    Row(
        Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
            .padding(horizontal = pad, vertical = if (dev.tv) 22.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        VodBtn(VIc.Back, size = 44.dp, onFocus = onInteract, onClick = onBack)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = if (dev.tv) 22.sp else 17.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            if (group.isNotBlank()) Text(group, fontSize = 12.sp, color = Accent, maxLines = 1)
        }
        Text(
            tr(R.string.a_76ad5744), fontSize = 12.sp, color = Color(0xFFFF8A8F),
            modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(8.dp)).padding(8.dp)
        )
        if (dev.touch) {
            VodBtn(VIc.Pip, size = 44.dp, onFocus = onInteract, onClick = onPip)
            VodBtn(VIc.Lock, size = 44.dp, onFocus = onInteract, onClick = onLock)
        }
    }

    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(horizontal = pad, vertical = if (dev.tv) 20.dp else 10.dp)
    ) {
        if (nowTitle != null || timeLine != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!logo.isNullOrBlank()) coil.compose.AsyncImage(model = logo, contentDescription = null, modifier = Modifier.size(40.dp))
                Column(Modifier.weight(1f)) {
                    if (nowTitle != null) Text(nowTitle, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (nowProgress != null) {
                        Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(3.dp).background(Color(0x33FFFFFF))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(nowProgress).background(Accent))
                        }
                    }
                    if (timeLine != null) Text(timeLine, fontSize = 12.sp, color = Color(0xCCFFFFFF), maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (ui.duration > 0) {
            PlayerTimeline(
                ui = ui, seekInterval = seekInterval, thumbnails = thumbnails,
                onSeekBy = onSeekBy, onSeekTo = onSeekTo, onInteract = onInteract,
                modifier = Modifier, focusRequester = timelineFocus, onToggle = onToggle
            )
            Spacer(Modifier.height(10.dp))
        }
        val transport: @Composable () -> Unit = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(horizontalArrangement = Arrangement.spacedBy(if (dev.tv) 14.dp else if (narrow) 2.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    VodBtn(VIc.Back10, size = side, onFocus = onInteract, onClick = onRewind)
                    VodBtn(if (ui.wantPlay) VIc.Pause else VIc.Play, size = big, primary = true, focusRequester = playFocus, onFocus = onInteract, onClick = onToggle)
                    VodBtn(VIc.Fwd10, size = side, onFocus = onInteract) { if (behindLive) onLiveEdge() else onSeekBy(seekInterval * 1_000L) }
                }
            }
        }
        val options: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                CaptionBtn(VIc.Channels, tr(R.string.cap_channels), side, onInteract, onChannels)
                CaptionBtn(VIc.Subs, tr(R.string.cap_subs_audio), side, onInteract, onSubs)
                CaptionBtn(VIc.Settings, tr(R.string.cap_settings), side, onInteract, onSettings)
            }
        }
        if (narrow) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                transport(); options()
            }
        } else {
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.align(Alignment.Center)) { transport() }
                Box(Modifier.align(Alignment.CenterEnd)) { options() }
            }
        }
    }
}

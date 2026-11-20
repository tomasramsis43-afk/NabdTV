package com.nabd.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
enum class VIc { Play, Pause, Prev, Next, Back10, Fwd10, Subs, Episodes, Settings, Back, Lock, Pip }

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
            val mirror = ic == VIc.Back10
            dsScale(if (mirror) -1f else 1f, 1f, pivot = Offset(size.width / 2, size.height / 2)) {
                drawArc(c, -60f, 300f, false, p(4f, 5f), Size(16f * u, 16f * u), style = st)
                // arrow head at the end of the sweep (top-left), pointing clockwise
                val a = Math.toRadians(-120.0)
                val cx = 12f; val cy = 13f; val r = 8f
                val px = cx + r * cos(a).toFloat(); val py = cy + r * sin(a).toFloat()
                val tx = -sin(a).toFloat(); val ty = cos(a).toFloat()
                val nx = -ty; val ny = tx
                tri(
                    p(px + 3.4f * tx, py + 3.4f * ty),
                    p(px - 1.2f * tx + 2.6f * nx, py - 1.2f * ty + 2.6f * ny),
                    p(px - 1.2f * tx - 2.6f * nx, py - 1.2f * ty - 2.6f * ny)
                )
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
        VIc.Settings -> {
            line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f)
            for ((x, y) in listOf(9f to 7f, 15.5f to 12f, 8f to 17f)) {
                drawCircle(Color(0xFF101522), 2.9f * u, p(x, y)); drawCircle(c, 2.9f * u, p(x, y), style = st)
            }
        }
        VIc.Back -> {
            val x = { v: Float -> if (rtl) 24f - v else v }
            drawPath(Path().apply { moveTo(x(15f) * u, 5f * u); lineTo(x(8f) * u, 12f * u); lineTo(x(15f) * u, 19f * u) }, c, style = st)
        }
        VIc.Lock -> {
            drawRoundRect(c, p(6f, 11f), Size(12f * u, 9f * u), CornerRadius(2.4f * u))
            drawArc(c, 180f, 180f, false, p(8.6f, 4.5f), Size(6.8f * u, 6.8f * u), style = st)
            line(8.6f, 8f, 8.6f, 11f); line(15.4f, 8f, 15.4f, 11f)
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
        if (ic == VIc.Back10 || ic == VIc.Fwd10) {
            Text("10", fontSize = (size.value * 0.3f).sp, fontWeight = FontWeight.Bold, color = tint, modifier = Modifier.padding(top = size * 0.12f))
        }
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
        primary -> Accent
        else -> Color(0x33FFFFFF)
    }
    val fg = when {
        !enabled -> Color(0x55FFFFFF)
        focused -> Color(0xFF101522)
        primary -> OnAccent
        else -> Color.White
    }
    Box(
        modifier.size(size).scale(sc)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .clip(CircleShape).background(bg)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0x33FFFFFF), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { VIcon(ic, fg, size * 0.5f) }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CaptionBtn(ic: VIc, label: String, size: Dp, onFocus: () -> Unit, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        VodBtn(ic, size = size, onFocus = onFocus, onClick = onClick)
        Text(label, fontSize = 10.sp, color = Color(0xCCFFFFFF), maxLines = 1)
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
    val side = if (dev.tv) 56.dp else 48.dp
    val big = if (dev.tv) 72.dp else 62.dp
    val pad = if (dev.tv) 48.dp else 14.dp

    // Top
    Row(
        Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
            .padding(horizontal = pad, vertical = if (dev.tv) 22.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        VodBtn(VIc.Back, size = 44.dp, onFocus = onInteract, onClick = onBack)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = if (dev.tv) 22.sp else 17.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 12.sp, color = Accent, maxLines = 1)
        }
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
                Row(horizontalArrangement = Arrangement.spacedBy(if (dev.tv) 18.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hasPrev) VodBtn(VIc.Prev, size = side, onFocus = onInteract, onClick = onPrev)
                    VodBtn(VIc.Back10, size = side, onFocus = onInteract) { onSeekBy(-seekInterval * 1_000L) }
                    VodBtn(if (ui.wantPlay) VIc.Pause else VIc.Play, size = big, primary = true, onFocus = onInteract, onClick = onToggle)
                    VodBtn(VIc.Fwd10, size = side, onFocus = onInteract) { onSeekBy(seekInterval * 1_000L) }
                    if (hasNext) VodBtn(VIc.Next, size = side, onFocus = onInteract, onClick = onNext)
                }
            }
        }
        val options: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                CaptionBtn(VIc.Subs, "الترجمة والصوت", side, onInteract, onSubs)
                if (hasEpisodes) CaptionBtn(VIc.Episodes, "الحلقات", side, onInteract, onEpisodes)
                CaptionBtn(VIc.Settings, "الإعدادات", side, onInteract, onSettings)
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

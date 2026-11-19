package com.nabd.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

fun fmtTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/** Seek step grows while the key is held: 10 s → 30 s → 1 min → 3 min. */
fun seekStep(repeat: Int): Long = when {
    repeat < 4 -> 10_000L
    repeat < 12 -> 30_000L
    repeat < 30 -> 60_000L
    else -> 180_000L
}

/**
 * Remote-first controls for movies and episodes: a focusable seek bar (Left/Right scrub, hold to go faster)
 * and one row of buttons below it. Always laid out left-to-right so the timeline matches the arrow keys.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VodControls(
    title: String, pos: Long, dur: Long, playing: Boolean, ctlMode: Boolean, hasNext: Boolean,
    ratioLabel: String, speed: Float,
    onToggle: () -> Unit, onSeekBy: (Long) -> Unit, onSeekTo: (Long) -> Unit, onNext: () -> Unit,
    onAspect: () -> Unit, onOptions: () -> Unit, onStats: () -> Unit, onTouch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(ctlMode) { if (ctlMode) runCatching { playFocus.requestFocus() } }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                .padding(horizontal = if (LocalDevice.current.tv) 48.dp else 16.dp, vertical = 14.dp)
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            SeekBar(pos, dur, onSeekBy, onSeekTo, onTouch)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.horizontalScrollSafe(),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                MBtn("⏪ 10", filled = false) { onSeekBy(-10_000) }
                MBtn(if (playing) "⏸ إيقاف" else "▶ تشغيل", modifier = Modifier.focusRequester(playFocus)) { onToggle() }
                MBtn("10 ⏩", filled = false) { onSeekBy(10_000) }
                if (hasNext) MBtn("⏭ التالية", filled = false) { onNext() }
                MBtn("⚙ الصوت والترجمة", filled = false) { onOptions() }
                MBtn("⛶ $ratioLabel", filled = false) { onAspect() }
                if (speed != 1f) Text("${speed}x", color = Accent, fontSize = 14.sp)
                MBtn("📊", filled = false) { onStats() }
            }
        }
    }
}

private fun Modifier.horizontalScrollSafe(): Modifier = this.then(Modifier.fillMaxWidth())

@Composable
private fun SeekBar(pos: Long, dur: Long, onSeekBy: (Long) -> Unit, onSeekTo: (Long) -> Unit, onTouch: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(fmtTime(pos), color = Color.White, fontSize = 13.sp, modifier = Modifier.width(62.dp))
        Box(
            Modifier.weight(1f).height(28.dp)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionLeft -> { onSeekBy(-seekStep(ev.nativeKeyEvent.repeatCount)); true }
                        Key.DirectionRight -> { onSeekBy(seekStep(ev.nativeKeyEvent.repeatCount)); true }
                        else -> false
                    }
                }
                .focusable()
                .pointerInput(dur) {
                    detectTapGestures { o -> if (dur > 0) { onSeekTo((o.x / size.width * dur).toLong()); onTouch() } }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            val h = if (focused) 10.dp else 5.dp
            Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(5.dp)).background(Color(0x44FFFFFF)))
            Box(Modifier.fillMaxWidth(frac).height(h).clip(RoundedCornerShape(5.dp)).background(Accent))
            if (focused) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.offset(x = (maxWidth * frac) - 9.dp).size(18.dp).clip(CircleShape)
                            .background(Color.White).border(2.dp, Accent, CircleShape)
                    )
                }
            }
        }
        Text(fmtTime(dur), color = Color.White, fontSize = 13.sp, modifier = Modifier.width(62.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

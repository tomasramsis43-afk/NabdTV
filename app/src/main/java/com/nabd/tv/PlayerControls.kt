@file:kotlin.OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.tv.material3.ExperimentalTvMaterial3Api::class
)

package com.nabd.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** Tabs of the in-player settings panel. */
enum class PlayerTab(val label: String) {
    Playback("التشغيل"),
    Episodes("الحلقات"),
    Audio("الصوت"),
    Subtitles("الترجمة"),
    Display("الصورة"),
    Gestures("الإيماءات"),
    Info("معلومات")
}

/** Everything the player HUD needs, kept in one observable holder so the video surface never recomposes. */
class PlayerUiState {
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    /** Incremented whenever persisted player preferences change, so dependent UI re-reads them. */
    var settingsRev by mutableIntStateOf(0)
    var buffered by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    /** playWhenReady: what the play button will do next (isPlaying is false while buffering). */
    var wantPlay by mutableStateOf(true)
    var speed by mutableFloatStateOf(1f)

    var aspect by mutableStateOf(AspectMode.Fit)
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    var rotation by mutableIntStateOf(0)

    var locked by mutableStateOf(false)
    var lockHint by mutableStateOf(false)
    var overlay by mutableStateOf(true)
    var focusMode by mutableStateOf(false)
    var ping by mutableIntStateOf(0)

    var sheet by mutableStateOf<PlayerTab?>(null)
    var sheetRev by mutableIntStateOf(0)
    var panel by mutableStateOf(false)
    var stats by mutableStateOf(false)
    var infoText by mutableStateOf("")
    var status by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var errorDetail by mutableStateOf("")

    var abStart by mutableLongStateOf(-1L)
    var abEnd by mutableLongStateOf(-1L)
    var loop by mutableStateOf(false)
    var sleepEnd by mutableStateOf(false)
    var resumeAt by mutableLongStateOf(-1L)
    var showJump by mutableStateOf(false)
    var nextEp by mutableStateOf<Episode?>(null)
    var ended by mutableStateOf(false)
    var digits by mutableStateOf("")
    var hudText by mutableStateOf("")

    var brightness by mutableFloatStateOf(0.5f)
    var volume by mutableIntStateOf(0)
    var volumeMax by mutableIntStateOf(15)

    var extSub by mutableStateOf<ExternalSubtitle?>(null)
    var extCues by mutableStateOf<List<SubCue>>(emptyList())
    var extCharset by mutableStateOf("")
    var extError by mutableStateOf<String?>(null)
    var subOn by mutableStateOf(true)
    var activeCue by mutableStateOf<SubCue?>(null)

    var audioTracks by mutableStateOf<List<PlayerTrack>>(emptyList())
    var textTracks by mutableStateOf<List<PlayerTrack>>(emptyList())
    var tracksRev by mutableIntStateOf(0)

    var videoW by mutableIntStateOf(0)
    var videoH by mutableIntStateOf(0)
    var videoAr by mutableFloatStateOf(0f)
    var surfaceW by mutableIntStateOf(0)
    var surfaceH by mutableIntStateOf(0)

    var mediaUrl by mutableStateOf("")
    var seekInterval by mutableIntStateOf(10)
}

/** Player commands handed to the UI. Every entry performs a real action on the engine. */
class PlayerActions(
    val togglePlay: () -> Unit,
    val play: () -> Unit,
    val pause: () -> Unit,
    val stopPlayback: () -> Unit,
    val seekBy: (Long) -> Unit,
    val seekTo: (Long) -> Unit,
    val restart: () -> Unit,
    val jumpTo: (Long) -> Unit,
    val nextVideo: () -> Unit,
    val prevVideo: () -> Unit,
    val nextEpisode: () -> Unit,
    val prevEpisode: () -> Unit,
    val setSpeed: (Float) -> Unit,
    val setAspect: (AspectMode) -> Unit,
    val zoomBy: (Float) -> Unit,
    val resetZoom: () -> Unit,
    val rotate: () -> Unit,
    val resetTransform: () -> Unit,
    val setLoop: (Boolean) -> Unit,
    val setPointA: () -> Unit,
    val setPointB: () -> Unit,
    val clearAb: () -> Unit,
    val setSleep: (Int) -> Unit,
    val setSleepEnd: () -> Unit,
    val cancelSleep: () -> Unit,
    val toggleLock: () -> Unit,
    val enterPip: () -> Unit,
    val hideControls: () -> Unit,
    val openChannels: () -> Unit,
    val loadSubtitle: () -> Unit,
    val removeSubtitle: () -> Unit,
    val selectSubtitle: (Int) -> Unit,
    val setSubtitleEncoding: (String) -> Unit,
    val setSubtitleDelay: (Int) -> Unit,
    val setSubtitlesEnabled: (Boolean) -> Unit,
    val setAudioTrack: (PlayerTrack) -> Unit,
    val clearAudioOverride: () -> Unit,
    val setAudioDelay: (Int) -> Unit,
    val adjustBrightness: (Float) -> Unit,
    val resetBrightness: () -> Unit,
    val setVolumePercent: (Float) -> Unit,
    val adjustVolume: (Boolean) -> Unit,
    val toggleMute: () -> Unit,
    val toggleAudioBoost: () -> Unit,
    val recreatePlayer: () -> Unit,
    val retry: () -> Unit,
    val openExternal: () -> Unit,
    val interact: () -> Unit,
    val changed: () -> Unit,
    val useSubtitle: (ExternalSubtitle) -> Unit = {}
)

/* ------------------------------------------------------------------ */
/* Building blocks                                                     */
/* ------------------------------------------------------------------ */

/**
 * Moves remote focus to this element, retrying for a moment. A single requestFocus() right after composition is
 * silently ignored when the target is not attached/laid out yet, which left the D-pad with no focus at all
 * (so the arrows "did nothing" in the sheet and the dialogs).
 */
suspend fun FocusRequester.focusWhenReady(attempts: Int = 12, waitMs: Long = 60L) {
    repeat(attempts) {
        kotlinx.coroutines.delay(waitMs)
        val ok = try { requestFocus(); true } catch (e: Exception) { false }
        if (ok) { kotlinx.coroutines.delay(120); runCatching { requestFocus() }; return }   // confirm once the layout settled
    }
}

/** Focusable pill used throughout the player. Focus is always visible (TV) and touch works normally. */
@Composable
fun PlayerButton(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    big: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(if (big) 30.dp else 14.dp)
    val h = if (big) 58.dp else if (LocalDevice.current.tv) 44.dp else 42.dp
    Box(
        modifier
            .heightIn(min = h)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    !enabled -> Color(0x33101010)
                    active -> Accent
                    focused -> Color(0xFF2A2F55)
                    else -> Color(0xAA151824)
                }
            )
            .border(
                if (focused) 3.dp else 1.dp,
                when {
                    focused -> Color.White
                    active -> Accent
                    else -> Color(0x44FFFFFF)
                },
                shape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (big) 22.dp else 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontSize = if (big) 19.sp else 14.sp, maxLines = 1,
            fontWeight = if (active || focused) FontWeight.Bold else FontWeight.Medium,
            color = when {
                !enabled -> Color(0x66FFFFFF)
                active -> OnAccent
                else -> Color.White
            }
        )
    }
}

/** Round icon-like button (play, close, lock...). */
@Composable
fun PlayerRoundButton(
    glyph: String,
    modifier: Modifier = Modifier,
    size: Int = 46,
    active: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(size.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(if (active) Accent else if (focused) Color(0xFF2A2F55) else Color(0xAA151824))
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color(0x44FFFFFF), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            glyph, fontSize = (size * 0.42f).sp, color = if (enabled) Color.White else Color(0x66FFFFFF),
            fontWeight = FontWeight.Bold
        )
    }
}

/* ------------------------------------------------------------------ */
/* Timeline                                                           */
/* ------------------------------------------------------------------ */

@Composable
fun PlayerTimeline(
    ui: PlayerUiState,
    seekInterval: Int,
    thumbnails: ThumbnailLoader?,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onInteract: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onToggle: (() -> Unit)? = null
) {
    val duration = ui.duration
    var focused by remember { mutableStateOf(false) }
    var dragPos by remember { mutableStateOf<Long?>(null) }
    val pos = dragPos ?: ui.position
    val frac = if (duration > 0) (pos.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bufferFrac = if (duration > 0) (ui.buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val showPreview = dragPos != null || focused

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier.fillMaxWidth()) {
            // Preview bubble / thumbnail
            if (showPreview && duration > 0) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ScrubThumbnail(thumbnails, pos)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xDD000000)).padding(horizontal = 12.dp, vertical = 5.dp)) {
                            Text(
                                fmtTime(pos) + (if (dragPos != null) "  (${if (pos >= ui.position) "+" else "−"}${fmtTime(kotlin.math.abs(pos - ui.position))})" else ""),
                                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    fmtTime(pos), color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.widthIn(min = 54.dp)
                )
                Text(
                    " / " + fmtTime(duration), color = Color(0xFFB8C2CF), fontSize = 11.sp,
                    modifier = Modifier.widthIn(min = 58.dp)
                )
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .height(if (LocalDevice.current.tv) 34.dp else 40.dp)
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        .onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (ev.key) {
                                Key.DirectionLeft -> {
                                    val base = seekInterval.coerceAtLeast(1) * 1000L
                                    onSeekBy(-seekAccelerated(base, ev.nativeKeyEvent.repeatCount)); true
                                }
                                Key.DirectionRight -> {
                                    val base = seekInterval.coerceAtLeast(1) * 1000L
                                    onSeekBy(seekAccelerated(base, ev.nativeKeyEvent.repeatCount)); true
                                }
                                Key.DirectionCenter, Key.Enter -> if (onToggle != null) { onToggle(); true } else false
                                else -> false
                            }
                        }
                        .focusable()
                        .pointerInput(duration, seekInterval) {
                            detectTapGestures { o ->
                                if (duration > 0) {
                                    onSeekTo((o.x / size.width * duration).toLong().coerceIn(0L, duration))
                                    onInteract()
                                }
                            }
                        }
                        .pointerInput(duration) {
                            detectHorizontalDragGestures(
                                onDragStart = { o ->
                                    if (duration > 0) dragPos = (o.x / size.width * duration).toLong().coerceIn(0L, duration)
                                    onInteract()
                                },
                                onDragEnd = {
                                    dragPos?.let { if (duration > 0) onSeekTo(it) }
                                    dragPos = null
                                    onInteract()
                                },
                                onDragCancel = { dragPos = null }
                            ) { change, _ ->
                                change.consume()
                                if (duration > 0) dragPos = (change.position.x / size.width * duration).toLong().coerceIn(0L, duration)
                                onInteract()
                            }
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    val barH = if (focused || dragPos != null) 10.dp else 5.dp
                    Box(Modifier.fillMaxWidth().height(barH).clip(RoundedCornerShape(6.dp)).background(Color(0x3DFFFFFF)))
                    Box(Modifier.fillMaxWidth(bufferFrac).height(barH).clip(RoundedCornerShape(6.dp)).background(Color(0x66FFFFFF)))
                    Box(Modifier.fillMaxWidth(frac).height(barH).clip(RoundedCornerShape(6.dp)).background(Accent))

                    // A-B repeat region
                    if (ui.abStart >= 0 && ui.abEnd > ui.abStart && duration > 0) {
                        val a = (ui.abStart.toFloat() / duration).coerceIn(0f, 1f)
                        val b = (ui.abEnd.toFloat() / duration).coerceIn(0f, 1f)
                        Box(
                            Modifier.offset(x = maxWidth * a).width(maxWidth * (b - a)).height(barH)
                                .background(Color(0x66FFD54F))
                        )
                        Box(Modifier.offset(x = maxWidth * a).size(8.dp).clip(CircleShape).background(Color(0xFFFFD54F)))
                        Box(Modifier.offset(x = maxWidth * b - 8.dp).size(8.dp).clip(CircleShape).background(Color(0xFFFFD54F)))
                    }

                    if (focused || dragPos != null) {
                        Box(
                            Modifier.offset(x = (maxWidth * frac).coerceIn(0.dp, maxWidth - 14.dp) - 9.dp)
                                .size(18.dp).clip(CircleShape).background(Color.White)
                                .border(2.dp, Accent, CircleShape)
                        )
                    }
                }
                Text(
                    "−" + fmtTime((duration - pos).coerceAtLeast(0)), color = Color(0xFFB8C2CF),
                    fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 62.dp)
                )
            }
        }
    }
}

private fun seekAccelerated(base: Long, repeatCount: Int): Long = when {
    repeatCount < 4 -> base
    repeatCount < 12 -> base * 3
    repeatCount < 30 -> base * 6
    else -> base * 12
}

/* ------------------------------------------------------------------ */
/* Gesture HUD                                                        */
/* ------------------------------------------------------------------ */

@Composable
fun GestureHud(feedback: GestureFeedback, modifier: Modifier = Modifier) {
    if (!feedback.active && feedback.text.isEmpty()) return
    val (icon, value) = when (feedback.kind) {
        GestureKind.Brightness -> "☀" to "${(feedback.progress * 100).toInt()}%"
        GestureKind.Volume -> "🔊" to "${(feedback.progress * 100).toInt()}%"
        GestureKind.Seek -> "⏱" to fmtTime(feedback.seekTarget)
        GestureKind.Zoom -> "🔍" to "${(feedback.progress * 100).toInt()}%"
        GestureKind.Pan -> "✥" to ""
        GestureKind.None -> "" to ""
    }
    Column(
        modifier.background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (feedback.kind != GestureKind.None) {
            Text("$icon  $value", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (feedback.kind == GestureKind.Seek) {
                Text(
                    (if (feedback.seekDelta >= 0) "+" else "−") + fmtTime(kotlin.math.abs(feedback.seekDelta)),
                    color = Accent, fontSize = 16.sp
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(180.dp).height(6.dp).clip(CircleShape).background(Color(0x44FFFFFF))) {
                    Box(Modifier.fillMaxWidth(feedback.progress.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(Accent))
                }
            }
        } else {
            Text(feedback.text, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/* ------------------------------------------------------------------ */
/* Resume / error / jump                                              */
/* ------------------------------------------------------------------ */

@Composable
fun ResumePrompt(position: Long, onResume: () -> Unit, onRestart: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.focusWhenReady() }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).background(Color(0xF0121622), RoundedCornerShape(20.dp))
                .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(20.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("متابعة المشاهدة؟", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("آخر موضع: ${fmtTime(position)}", color = Color(0xFFB8C2CF), fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayerButton("▶ استكمال", focusRequester = focus) { onResume() }
                PlayerButton("⟲ من البداية", active = false) { onRestart() }
            }
        }
    }
}

@Composable
fun PlayerErrorCard(
    message: String,
    detail: String,
    onRetry: () -> Unit,
    onExternal: () -> Unit,
    onExit: () -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.focusWhenReady() }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).background(Color(0xF0121622), RoundedCornerShape(20.dp))
                .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(20.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("تعذّر التشغيل", color = Color(0xFFFF8A8F), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(message, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(detail, color = Color(0xFF9AA3B8), fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 4)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlayerButton("↻ إعادة المحاولة", focusRequester = focus) { onRetry() }
                PlayerButton("مشغّل خارجي") { onExternal() }
                PlayerButton("خروج") { onExit() }
            }
        }
    }
}

/** TV-friendly timestamp picker: D-pad changes hours/minutes/seconds, OK repeats the action. */
@Composable
fun TimeJumpDialog(current: Long, duration: Long, onJump: (Long) -> Unit, onDismiss: () -> Unit) {
    var h by remember { mutableIntStateOf((current / 3_600_000L).toInt()) }
    var m by remember { mutableIntStateOf(((current / 60_000L) % 60).toInt()) }
    var sec by remember { mutableIntStateOf(((current / 1000L) % 60).toInt()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.focusWhenReady() }
    val total = ((h * 3600L + m * 60L + sec) * 1000L).coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)

    fun shift(unit: Int, delta: Int) {
        when (unit) {
            0 -> h = (h + delta).coerceIn(0, 23)
            1 -> m = (m + delta).coerceIn(0, 59)
            else -> sec = (sec + delta).coerceIn(0, 59)
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).background(Color(0xF0121622), RoundedCornerShape(20.dp))
                .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(20.dp)).padding(24.dp)
                .clickable(enabled = false) {},
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("الانتقال إلى وقت محدد", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TimeColumn("ساعات", h) { shift(0, it) }
                Text(":", color = Color.White, fontSize = 28.sp)
                TimeColumn("دقائق", m) { shift(1, it) }
                Text(":", color = Color.White, fontSize = 28.sp)
                TimeColumn("ثوانٍ", sec) { shift(2, it) }
            }
            Spacer(Modifier.height(12.dp))
            Text("الوقت المطلوب: ${fmtTime(total)}", color = Accent, fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlayerButton("انتقال", focusRequester = focus) { onJump(total); onDismiss() }
                PlayerButton("إلغاء") { onDismiss() }
            }
        }
    }
}

@Composable
private fun TimeColumn(label: String, value: Int, onDelta: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PlayerButton("＋") { onDelta(1) }
        Text("%02d".format(value), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
        PlayerButton("－") { onDelta(-1) }
        Text(label, color = Color(0xFF9AA3B8), fontSize = 11.sp)
    }
}

/* ------------------------------------------------------------------ */
/* Subtitle overlay                                                   */
/* ------------------------------------------------------------------ */

@Composable
fun ExternalSubtitleOverlay(ui: PlayerUiState, store: Store, modifier: Modifier = Modifier) {
    val cue = ui.activeCue ?: return
    if (!store.subEnabled || !ui.subOn) return
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sizeSp = with(density) { (maxHeight.toPx() * store.subSize / 1.2f).toSp() }
        Text(
            cue.text,
            color = Color(store.subColor),
            fontSize = sizeSp.value.coerceIn(12f, 44f).sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            style = androidx.compose.ui.text.TextStyle(
                shadow = if (store.subOutline) androidx.compose.ui.graphics.Shadow(Color.Black, androidx.compose.ui.geometry.Offset(1.5f, 1.5f), 4f) else null
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 28.dp, end = 28.dp, bottom = (maxHeight * store.subPosition).value.coerceAtLeast(12f).dp)
                .widthIn(max = maxWidth * 0.9f)
                .background(
                    if (store.subBackground) Color(0x99000000) else Color.Transparent,
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

package com.nabd.tv

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.Modifier
import kotlin.math.abs
import kotlin.math.min

/** Live visual feedback shown in the middle of the video while a gesture is active. */
class GestureFeedback {
    var kind by mutableStateOf(GestureKind.None)
    var text by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var seekTarget by mutableLongStateOf(0L)
    var seekDelta by mutableLongStateOf(0L)
    var active by mutableStateOf(false)

    fun clear() {
        kind = GestureKind.None
        text = ""
        progress = 0f
        active = false
        seekTarget = 0L
        seekDelta = 0L
    }
}

enum class GestureKind { None, Brightness, Volume, Seek, Zoom, Pan }

/**
 * Player touch gestures, all configurable:
 *  - tap = show/hide controls, long press = player menu
 *  - double tap left / right = seek, centre = play/pause
 *  - vertical drag on the left = brightness, on the right = volume
 *  - horizontal drag = scrub preview, release = seek
 *  - pinch = zoom, two-finger or (while zoomed) one-finger drag = move the picture
 *
 * Gestures only start after the touch slop, so simple taps on buttons/timeline never conflict.
 */
fun Modifier.playerGesturesV2(
    key: Any?,
    enabled: Boolean,
    seekEnabled: Boolean,
    brightnessEnabled: Boolean,
    volumeEnabled: Boolean,
    doubleTapEnabled: Boolean,
    pinchEnabled: Boolean,
    panEnabled: Boolean,
    sensitivity: Float,
    canSeek: () -> Boolean,
    duration: () -> Long,
    position: () -> Long,
    zoomed: () -> Boolean,
    onTap: () -> Unit,
    onDoubleTap: (zone: Int) -> Unit,
    onLongPress: () -> Unit,
    onSeekStart: () -> Unit,
    onSeekPreview: (target: Long, delta: Long) -> Unit,
    onSeekCommit: (target: Long) -> Unit,
    onBrightness: (delta: Float) -> Unit,
    onVolume: (delta: Float) -> Unit,
    onZoom: (zoomRatio: Float, pan: Offset) -> Unit,
    onPan: (Offset) -> Unit,
    onGestureEnd: () -> Unit
): Modifier = this
    .pointerInput(key, enabled, seekEnabled, brightnessEnabled, volumeEnabled, doubleTapEnabled) {
        if (!enabled) return@pointerInput
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { o ->
                if (!doubleTapEnabled) return@detectTapGestures
                val zone = when {
                    o.x < size.width * 0.35f -> -1
                    o.x > size.width * 0.65f -> 1
                    else -> 0
                }
                onDoubleTap(zone)
            },
            onLongPress = { onLongPress() }
        )
    }
    .pointerInput(key, enabled, seekEnabled, brightnessEnabled, volumeEnabled, pinchEnabled, panEnabled, sensitivity) {
        if (!enabled) return@pointerInput
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var mode = DragMode.None
            var total = Offset.Zero
            var multiSeen = false
            var seekBase = 0L
            var seekTarget = 0L
            var startX = down.position.x
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break

                if (pressed.size >= 2) {
                    multiSeen = true
                    if (pinchEnabled) {
                        if (mode == DragMode.None) {
                            mode = DragMode.Zoom
                        }
                        val z = event.calculateZoom()
                        val p = event.calculatePan()
                        onZoom(if (z.isFinite() && z > 0f) z else 1f, p)
                        event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                    }
                    continue
                }
                if (multiSeen) continue   // keep zooming until all fingers are lifted

                val change = pressed.first()
                val delta = change.positionChange()
                total += delta

                if (mode == DragMode.None && total.getDistance() > slop) {
                    startX = change.position.x - total.x
                    mode = when {
                        panEnabled && zoomed() -> DragMode.Pan
                        abs(total.x) > abs(total.y) * 1.15f ->
                            if (seekEnabled && canSeek()) DragMode.Seek else DragMode.Ignore
                        brightnessEnabled && startX < size.width * 0.37f -> DragMode.Brightness
                        volumeEnabled && startX > size.width * 0.63f -> DragMode.Volume
                        else -> DragMode.Ignore
                    }
                    if (mode == DragMode.Seek) {
                        seekBase = position().coerceAtLeast(0L)
                        seekTarget = seekBase
                        onSeekStart()
                    }
                }

                when (mode) {
                    DragMode.Brightness -> onBrightness(-delta.y / size.height * sensitivity)
                    DragMode.Volume -> onVolume(-delta.y / size.height * sensitivity)
                    DragMode.Seek -> {
                        val dur = duration()
                        val span = if (dur > 0) min(dur, 180_000L) else 180_000L
                        val max = if (dur > 0) (dur - 800L).coerceAtLeast(0L) else Long.MAX_VALUE
                        seekTarget = (seekBase + (total.x / size.width * span * sensitivity).toLong()).coerceIn(0L, max)
                        onSeekPreview(seekTarget, seekTarget - seekBase)
                    }
                    DragMode.Pan -> onPan(delta)
                    else -> {}
                }
                if (mode != DragMode.None) {
                    change.consume()
                }
            }
            when (mode) {
                DragMode.Seek -> onSeekCommit(seekTarget)
                else -> {}
            }
            if (mode != DragMode.None) onGestureEnd()
        }
    }

private enum class DragMode { None, Seek, Brightness, Volume, Zoom, Pan, Ignore }

/** Volume step used by the TV remote and the player menu (system stream, so passthrough devices work too). */
fun android.media.AudioManager.stepVolume(up: Boolean, steps: Int = 1) {
    val max = getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
    val v = (getStreamVolume(android.media.AudioManager.STREAM_MUSIC) + if (up) steps else -steps).coerceIn(0, max)
    setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0)
}

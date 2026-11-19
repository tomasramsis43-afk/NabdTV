@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nabd.tv

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Professional player screen.
 *
 * The video surface is never recomposed: all changing values live in [PlayerUiState].
 * Playback logic stays in [buildPlayer] / Media3; this file orchestrates UI, gestures,
 * TV keys, resume, A–B, sleep, external subtitles and all existing live-TV features.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val ch = vm.current ?: return
    val store = vm.store
    val live = vm.playMode == PlayMode.Live
    val vod = vm.playMode == PlayMode.Vod
    val dev = LocalDevice.current

    val ui = remember { PlayerUiState() }
    val feedback = remember { GestureFeedback() }
    val act = remember(ctx) { ctx.findActivity() }
    val am = remember(ctx) { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val vprefs = remember(ctx) { ctx.getSharedPreferences("player_view", Context.MODE_PRIVATE) }
    val thumbnails = rememberThumbnailLoader(ch.url)
    val chRef = rememberUpdatedState(ch)
    val chUrl by rememberUpdatedState(ch.url)

    val seriesKey = if (vod && kindOf(ch) == Kind.Series) vm.detail?.url else null
    val introEnd = seriesKey?.let { store.introEnd(it) } ?: 0L
    val showSkip = introEnd > 0 && ui.position < introEnd - 1_500L && vod
    val rKey = "ratio2:" + (seriesKey ?: ch.url).hashCode()

    // Changing the decoder / buffer profile rebuilds the engine; playback position is carried over.
    var epoch by remember { mutableIntStateOf(0) }
    var pendingAt by remember { mutableLongStateOf(-1L) }
    var pendingPlay by remember { mutableStateOf(true) }
    var pendingResume by remember { mutableLongStateOf(-1L) }
    val player = remember(epoch, live) { buildPlayer(ctx, store, live) }

    var view by remember { mutableStateOf<PlayerView?>(null) }
    var retries by remember { mutableIntStateOf(0) }
    var altUsed by remember { mutableStateOf(false) }
    var volAcc by remember { mutableFloatStateOf(0f) }
    var notedFor by remember { mutableStateOf("") }
    var startedAt by remember { mutableLongStateOf(0L) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    fun openSheet(tab: PlayerTab) {
        ui.sheet = tab
        ui.sheetRev++
        ui.overlay = true
        ui.ping++
    }

    fun hud(text: String) {
        ui.hudText = text
        ui.ping++
    }

    fun applyBrightness(value: Float) {
        ui.brightness = value.coerceIn(0.02f, 1f)
        act?.window?.let { w ->
            val lp = w.attributes
            lp.screenBrightness = ui.brightness
            w.attributes = lp
        }
    }

    fun applyVolumeDelta(delta: Float) {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volAcc += delta * max * 1.3f
        val steps = volAcc.toInt()
        if (steps != 0) {
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (current + steps).coerceIn(0, max), 0)
            volAcc -= steps
        }
        ui.volume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        ui.volumeMax = max
    }

    fun clearSubtitleFile() {
        store.clearExternalSubtitle(chRef.value.url)
        ui.extSub = null
        ui.extCues = emptyList()
        ui.extCharset = ""
        ui.extError = null
        ui.activeCue = null
        if (store.subEnabled) player.setSubtitlesEnabled(true)
        ui.settingsRev++
    }

    val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val copied = SubtitleFiles.copy(ctx, uri, chRef.value.url)
        if (copied == null) {
            vm.say("تعذّر قراءة ملف الترجمة")
        } else {
            val res = SubtitleFiles.load(copied)
            store.setExternalSubtitle(chRef.value.url, copied)
            store.subEncoding = "auto"
            ui.extSub = copied
            ui.extCues = res.cues
            ui.extCharset = res.charset
            ui.extError = res.error
            ui.subOn = true
            store.subEnabled = true
            player.setSubtitlesEnabled(false)   // avoid double subtitles with Media3's renderer
            ui.settingsRev++
            vm.say(
                if (res.cues.isEmpty()) res.error ?: "لم يتم العثور على ترجمة في الملف"
                else "تم تحميل ${res.cues.size} سطر ترجمة"
            )
        }
    }

    val actions = remember(player, rKey, seriesKey, subPicker) {
        PlayerActions(
            togglePlay = { player.playWhenReady = !player.playWhenReady; ui.ping++ },
            play = { player.playWhenReady = true; ui.ping++ },
            pause = { player.playWhenReady = false; ui.ping++ },
            stopPlayback = { vm.stop() },
            seekBy = { delta ->
                val max = if (player.duration > 0) player.duration - 500L else Long.MAX_VALUE
                val t = (player.currentPosition + delta).coerceIn(0L, maxOf(0L, max))
                player.seekTo(t)
                ui.position = t
                hud((if (delta < 0) "⏪ " else "⏩ ") + fmtTime(t))
            },
            seekTo = { t -> player.seekTo(t); ui.position = t; ui.ping++ },
            restart = {
                player.seekTo(0L)
                ui.position = 0L
                ui.ended = false
                if (player.playbackState == Player.STATE_ENDED) player.prepare()
                player.playWhenReady = true
                ui.ping++
            },
            jumpTo = { t ->
                val max = if (player.duration > 0) player.duration - 500L else Long.MAX_VALUE
                val target = t.coerceIn(0L, maxOf(0L, max))
                player.seekTo(target); ui.position = target; hud("⏱ ${fmtTime(target)}")
            },
            nextVideo = { vm.playNeighbor(1) },
            prevVideo = { vm.playNeighbor(-1) },
            nextEpisode = { if (vm.hasNextEpisode(chRef.value)) { vm.playNext(); ui.nextEp = null } },
            prevEpisode = { if (vm.hasPreviousEpisode(chRef.value)) vm.playPrevious() },
            setSpeed = { v ->
                player.setPlaybackSpeed(v); ui.speed = v; store.defaultSpeed = v; ui.settingsRev++
                hud("⏱ ${v}x")
            },
            setAspect = { mode ->
                ui.aspect = mode
                vprefs.edit().putString(rKey, mode.name).apply()
                ui.zoom = 1f; ui.panX = 0f; ui.panY = 0f
                hud("⛶ ${mode.label}")
            },
            zoomBy = { delta ->
                val z = (ui.zoom + delta).coerceIn(1f, 4f)
                ui.zoom = z
                if (z <= 1.001f) { ui.panX = 0f; ui.panY = 0f }
                hud("🔍 ${(z * 100).roundToInt()}%")
            },
            resetZoom = { ui.zoom = 1f; ui.panX = 0f; ui.panY = 0f; hud("🔍 100%") },
            rotate = { ui.rotation = (ui.rotation + 90) % 360; hud("↻ ${ui.rotation}°") },
            resetTransform = { ui.zoom = 1f; ui.panX = 0f; ui.panY = 0f; ui.rotation = 0; hud("↺") },
            setLoop = { on -> ui.loop = on; store.loopByDefault = on; ui.settingsRev++; hud(if (on) "🔁 تكرار" else "🔁 إيقاف التكرار") },
            setPointA = {
                ui.abStart = player.currentPosition
                if (ui.abEnd <= ui.abStart) ui.abEnd = -1L
                hud("A · ${fmtTime(ui.abStart)}")
            },
            setPointB = {
                if (ui.abStart < 0) vm.say("حدد النقطة A أولاً")
                else if (player.currentPosition <= ui.abStart) vm.say("يجب أن تكون B بعد A")
                else { ui.abEnd = player.currentPosition; hud("B · ${fmtTime(ui.abEnd)}") }
            },
            clearAb = { ui.abStart = -1L; ui.abEnd = -1L; hud("تم مسح A–B") },
            setSleep = { minutes ->
                if (minutes <= 0) { vm.sleepAt = 0L; ui.sleepEnd = false; vm.say("تم إلغاء المؤقت") }
                else {
                    vm.sleepAt = System.currentTimeMillis() + minutes * 60_000L
                    ui.sleepEnd = false
                    vm.say("سيتوقف التشغيل بعد $minutes دقيقة")
                }
                ui.settingsRev++
            },
            setSleepEnd = { ui.sleepEnd = true; vm.sleepAt = 0L; vm.say("سيتوقف بعد انتهاء المحتوى الحالي"); ui.settingsRev++ },
            cancelSleep = { ui.sleepEnd = false; vm.sleepAt = 0L; vm.say("تم إلغاء المؤقت"); ui.settingsRev++ },
            toggleLock = {
                ui.locked = !ui.locked
                ui.overlay = !ui.locked
                ui.lockHint = ui.locked
                ui.ping++
            },
            enterPip = {
                val ok = (act as? MainActivity)?.enterPip() == true
                if (!ok) vm.say("الجهاز لا يدعم النافذة العائمة")
            },
            hideControls = { ui.overlay = false },
            openChannels = { if (live) { ui.panel = true; ui.ping++ } else vm.say("قائمة القنوات متاحة في البث المباشر فقط") },
            loadSubtitle = { subPicker.launch(arrayOf("*/*")) },
            removeSubtitle = { clearSubtitleFile(); vm.say("تمت إزالة الترجمة الخارجية") },
            selectSubtitle = { index ->
                val track = ui.textTracks.getOrNull(index) ?: return@PlayerActions
                if (ui.extSub != null) clearSubtitleFile()
                player.selectTrack(C.TRACK_TYPE_TEXT, track)
                ui.subOn = true
                store.subEnabled = true
                player.setSubtitlesEnabled(true)
                ui.settingsRev++
            },
            setSubtitleEncoding = { enc ->
                store.subEncoding = enc
                ui.extSub?.let { sub ->
                    val updated = sub.copy(encoding = enc)
                    val res = SubtitleFiles.reDecode(sub, enc)
                    store.setExternalSubtitle(chRef.value.url, updated)
                    ui.extSub = updated
                    ui.extCues = res.cues
                    ui.extCharset = res.charset
                    ui.extError = res.error
                }
                ui.settingsRev++
                vm.say(if (enc == "auto") "كشف الترميز تلقائياً" else "تم تغيير الترميز إلى $enc")
            },
            setSubtitleDelay = { ms ->
                store.subDelayMs = ms.coerceIn(-10_000, 10_000)
                ui.settingsRev++
                hud("💬 ${store.subDelayMs} ms")
            },
            setSubtitlesEnabled = { on ->
                ui.subOn = on
                store.subEnabled = on
                if (on && ui.extSub == null) player.setSubtitlesEnabled(true)
                else if (!on) player.setSubtitlesEnabled(false)
                ui.settingsRev++
            },
            setAudioTrack = { track -> player.selectTrack(C.TRACK_TYPE_AUDIO, track); ui.tracksRev++; hud("🔊 ${track.label}") },
            clearAudioOverride = { player.clearTrackOverride(C.TRACK_TYPE_AUDIO); hud("🔊 تلقائي") },
            setAudioDelay = { ms ->
                store.audioDelayMs = ms.coerceIn(-2_000, 2_000)
                AudioDelayBus.ms = store.audioDelayMs
                ui.settingsRev++
                hud("🔊 ${store.audioDelayMs} ms")
            },
            adjustBrightness = { delta ->
                applyBrightness(ui.brightness + delta)
                feedback.kind = GestureKind.Brightness
                feedback.progress = ui.brightness
                feedback.active = true
            },
            resetBrightness = {
                val current = act?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                applyBrightness(current)
                feedback.kind = GestureKind.Brightness; feedback.progress = ui.brightness; feedback.active = true
            },
            setVolumePercent = { percent ->
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (percent * max).roundToInt().coerceIn(0, max), 0)
                ui.volume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                feedback.kind = GestureKind.Volume
                feedback.progress = percent.coerceIn(0f, 1f)
                feedback.active = true
            },
            adjustVolume = { up ->
                am.stepVolume(up)
                ui.volume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                ui.volumeMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                feedback.kind = GestureKind.Volume
                feedback.progress = ui.volume.toFloat() / ui.volumeMax.coerceAtLeast(1)
                feedback.active = true
                hud("🔊 ${ui.volume}/${ui.volumeMax}")
            },
            toggleMute = {
                if (am.isStreamMute(AudioManager.STREAM_MUSIC))
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                else
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                hud(if (am.isStreamMute(AudioManager.STREAM_MUSIC)) "🔇" else "🔊")
            },
            toggleAudioBoost = { store.audioBoost = !store.audioBoost; ui.settingsRev++; hud(if (store.audioBoost) "🔊 +8 dB" else "🔊") },
            recreatePlayer = {
                pendingAt = player.currentPosition
                pendingPlay = player.playWhenReady
                runCatching {
                    player.playWhenReady = false
                    player.stop()
                }
                ui.status = "جارٍ إعادة بناء المحرك..."
                epoch++
                ui.ping++
            },
            retry = {
                retries = 0; altUsed = false
                ui.error = null; ui.errorDetail = ""; ui.status = "جارٍ التحميل..."
                player.prepare(); player.playWhenReady = true
                ui.ping++
            },
            openExternal = {
                runCatching {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(chRef.value.url)).setType("video/*")
                    ctx.startActivity(Intent.createChooser(i, "فتح بواسطة").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { vm.say("لا يوجد مشغّل مناسب على الجهاز") }
            },
            interact = { ui.overlay = true; ui.ping++ },
            changed = { ui.settingsRev++ }
        )
    }

    fun refreshTracks() {
        ui.audioTracks = player.audioTracks()
        ui.textTracks = player.textTracks()
        ui.tracksRev++
    }

    /* ------------------------------------------------------------------ */
    /* Initial state and appearance                                       */
    /* ------------------------------------------------------------------ */

    LaunchedEffect(Unit) {
        ui.volumeMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        ui.volume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        ui.brightness = act?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
        rootFocus.requestFocus()
    }

    LaunchedEffect(rKey) {
        val legacy = runCatching { vprefs.getInt("r:" + (seriesKey ?: ch.url).hashCode(), -1) }.getOrDefault(-1)
        val legacyMode = when (legacy) {
            0 -> AspectMode.Fit
            1 -> AspectMode.Fill
            2 -> AspectMode.Stretch
            3 -> AspectMode.Smart
            else -> null
        }
        val saved = runCatching { vprefs.getString(rKey, null) }.getOrNull()
        ui.aspect = saved?.let { AspectMode.of(it) } ?: legacyMode ?: AspectMode.of(store.defaultAspect)
        ui.zoom = 1f; ui.panX = 0f; ui.panY = 0f; ui.rotation = 0
        ui.abStart = -1L; ui.abEnd = -1L
        ui.loop = store.loopByDefault
        ui.seekInterval = store.seekInterval
        ui.subOn = store.subEnabled
    }

    LaunchedEffect(view, ui.settingsRev) {
        view?.subtitleView?.apply {
            setFractionalTextSize(store.subSize)
            setStyle(
                CaptionStyleCompat(
                    store.subColor,
                    if (store.subBackground) 0xAA000000.toInt() else Color.Transparent.value.toInt(),
                    0,
                    if (store.subOutline) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE,
                    0xFF000000.toInt(),
                    null
                )
            )
        }
    }

    // External subtitle remembered for this title.
    LaunchedEffect(ch.url, player) {
        ui.extSub = null; ui.extCues = emptyList(); ui.extCharset = ""; ui.extError = null; ui.activeCue = null
        val sub = store.externalSubtitle(ch.url)
        if (sub != null) {
            val res = SubtitleFiles.load(sub)
            if (res.error == null && res.cues.isNotEmpty()) {
                ui.extSub = sub
                ui.extCues = res.cues
                ui.extCharset = res.charset
                ui.extError = null
                if (store.subEnabled) player.setSubtitlesEnabled(false)
            } else {
                store.clearExternalSubtitle(ch.url)
            }
        } else if (store.subEnabled) {
            player.setSubtitlesEnabled(true)
        }
    }

    /* ------------------------------------------------------------------ */
    /* Engine lifecycle                                                   */
    /* ------------------------------------------------------------------ */

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = refreshTracks()

            override fun onVideoSizeChanged(size: VideoSize) {
                ui.videoW = size.width
                ui.videoH = size.height
                ui.videoAr = if (size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 0f
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) { ui.playing = isPlaying }
            override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) { ui.speed = parameters.speed }

            override fun onPlayerError(error: PlaybackException) {
                val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                val url = chUrl
                val alternate = when {
                    url.endsWith(".ts") -> url.removeSuffix(".ts") + ".m3u8"
                    url.endsWith(".m3u8") -> url.removeSuffix(".m3u8") + ".ts"
                    else -> null
                }
                when {
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        player.seekToDefaultPosition(); player.prepare()
                    }
                    retries < 2 && (code == null || code >= 500) -> {
                        retries++
                        player.prepare()
                    }
                    !altUsed && alternate != null -> {
                        altUsed = true; retries = 0
                        player.setMediaItem(mediaItemFor(alternate)); player.prepare(); player.playWhenReady = true
                        ui.status = "تجربة مصدر بديل..."
                    }
                    else -> {
                        vm.health.markFailure(url)
                        if (live && vm.failover()) ui.status = "تحويل تلقائي لمصدر بديل..."
                        else {
                            ui.error = "تعذّر تشغيل هذا المحتوى"
                            ui.errorDetail = (code?.let { "HTTP $it" } ?: error.errorCodeName) +
                                (error.cause?.message?.let { " · $it" } ?: "")
                            ui.status = ""
                        }
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> ui.status = "جارٍ التحميل..."
                    Player.STATE_READY -> {
                        ui.status = ""
                        retries = 0
                        if (pendingResume > 0) {
                            if (player.duration > pendingResume + 5_000L) {
                                player.playWhenReady = false
                                ui.resumeAt = pendingResume
                            }
                            pendingResume = -1L
                        }
                        if (LiveRewind.pending > 0) {
                            val seconds = LiveRewind.pending
                            LiveRewind.pending = 0
                            if (player.isCurrentMediaItemSeekable) player.seekTo((player.currentPosition - seconds * 1000L).coerceAtLeast(0L))
                            else vm.say("المزوّد لا يدعم الرجوع في هذه القناة")
                        }
                        if (live) {
                            vm.health.markSuccess(chUrl, System.currentTimeMillis() - startedAt)
                            refreshTracks()
                        }
                    }
                    Player.STATE_ENDED -> {
                        if (ui.loop && !live) {
                            player.seekTo(0L); player.playWhenReady = true
                        } else if (vod) {
                            vm.saveProgress(chRef.value, player.duration, player.duration)
                            if (ui.sleepEnd) {
                                ui.sleepEnd = false
                                vm.stop()
                            } else {
                                val next = vm.nextEpisodeOf(chRef.value)
                                if (next != null) {
                                    ui.nextEp = next
                                } else {
                                    ui.ended = true
                                }
                            }
                        } else if (live) {
                            ui.status = "انتهى البث أو انقطع الاتصال"
                        }
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Each engine rebuild creates a new ExoPlayer; the old one must be released (audio focus, decoder, network).
    DisposableEffect(player) {
        onDispose { runCatching { player.release() } }
    }

    LaunchedEffect(ch.url, player) {
        retries = 0; altUsed = false
        ui.error = null; ui.errorDetail = ""; ui.nextEp = null; ui.ended = false
        ui.hudText = ""; ui.resumeAt = -1L; ui.status = "جارٍ التحميل..."
        pendingResume = -1L
        startedAt = System.currentTimeMillis()

        val mode = vm.playMode
        val resume = when {
            pendingAt >= 0L -> pendingAt
            mode == PlayMode.Vod && store.rememberPosition &&
                ResumeMode.of(store.resumeMode) == ResumeMode.Always && vm.resumeAt > 5_000L -> vm.resumeAt
            else -> 0L
        }
        if (pendingAt < 0L && mode == PlayMode.Vod && store.rememberPosition &&
            ResumeMode.of(store.resumeMode) == ResumeMode.Ask && vm.resumeAt > 5_000L
        ) pendingResume = vm.resumeAt
        pendingAt = -1L

        player.setMediaItem(mediaItemFor(ch.url), if (resume > 0L) resume else C.TIME_UNSET)
        player.prepare()
        player.playWhenReady = pendingPlay && pendingResume <= 0L
        pendingPlay = true
        player.setPlaybackSpeed(if (live) 1f else store.defaultSpeed)
        ui.speed = player.playbackParameters.speed
        if (notedFor != ch.url) { vm.noteStart(ch); notedFor = ch.url }
        if (live) { delay(2_500); vm.prefetchNeighbors() }
    }

    // Position, cue, A–B and volume mirror. Runs independently of control visibility.
    LaunchedEffect(player) {
        while (true) {
            ui.position = player.currentPosition.coerceAtLeast(0L)
            ui.duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
            ui.buffered = player.bufferedPosition
            ui.playing = player.isPlaying
            ui.speed = player.playbackParameters.speed
            ui.volume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            ui.volumeMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            ui.activeCue = if (ui.extCues.isNotEmpty() && ui.subOn && store.subEnabled)
                SubtitleParser.active(ui.extCues, ui.position, store.subDelayMs) else null
            if (ui.abStart >= 0 && ui.abEnd > ui.abStart && ui.position >= ui.abEnd) player.seekTo(ui.abStart)
            delay(200)
        }
    }

    LaunchedEffect(ui.sheet, ui.stats, player) {
        while (true) {
            if (ui.sheet == PlayerTab.Info || ui.stats) ui.infoText = playbackStats(player)
            delay(1_000)
        }
    }

    /* ------------------------------------------------------------------ */
    /* Existing app features kept intact                                  */
    /* ------------------------------------------------------------------ */

    LaunchedEffect(ch.url) { PlaybackService.start(ctx, ch.name) }

    DisposableEffect(Unit) {
        PlaybackBus.stop = { vm.stop() }
        onDispose {
            PlaybackBus.stop = null
            PlaybackService.stop(ctx)
            vm.health.flush()
            act?.window?.let { w -> val lp = w.attributes; lp.screenBrightness = -1f; w.attributes = lp }
        }
    }

    // Stop sending video frames while the app is in the background (audio keeps playing).
    DisposableEffect(act, player) {
        val lc = (act as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP || event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                val disable = event == androidx.lifecycle.Lifecycle.Event.ON_STOP && !vm.inPip
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disable).build()
            }
        }
        lc?.addObserver(observer)
        onDispose { lc?.removeObserver(observer) }
    }

    // Save the position of the outgoing title before its media item is replaced.
    DisposableEffect(ch.url, player) {
        onDispose {
            if (store.rememberPosition && vm.playMode == PlayMode.Vod)
                vm.saveProgress(chRef.value, ui.position, ui.duration)
        }
    }

    LaunchedEffect(ch.url, player) {
        while (true) {
            delay(10_000)
            if (store.rememberPosition && vm.playMode == PlayMode.Vod)
                vm.saveProgress(chRef.value, ui.position, ui.duration)
        }
    }

    DisposableEffect(store.audioBoost, player, ui.tracksRev) {
        val enhancer = if (store.audioBoost) runCatching {
            LoudnessEnhancer(player.audioSessionId).apply { setTargetGain(800); enabled = true }
        }.getOrNull() else null
        onDispose { runCatching { enhancer?.release() } }
    }

    val originalOrientation = remember(act) { act?.requestedOrientation }
    DisposableEffect(Unit) {
        onDispose {
            if (!dev.tv) act?.requestedOrientation = originalOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(ui.settingsRev, dev.tv) {
        if (!dev.tv && act != null) {
            act.requestedOrientation = when (OrientationPref.of(store.playerOrientation)) {
                OrientationPref.Auto -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                OrientationPref.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                OrientationPref.Portrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
    }

    LaunchedEffect(ui.digits) {
        if (ui.digits.isNotEmpty()) {
            delay(1_500)
            ui.digits.toIntOrNull()?.let { vm.zapToNumber(it) }
            ui.digits = ""
        }
    }

    LaunchedEffect(ui.hudText) {
        if (ui.hudText.isNotEmpty()) {
            delay(1_100)
            if (ui.hudText.isNotEmpty()) ui.hudText = ""
        }
    }

    LaunchedEffect(ui.lockHint) {
        if (ui.lockHint) { delay(1_400); ui.lockHint = false }
    }

    LaunchedEffect(ui.ping, ui.overlay, ui.panel, ui.sheet, ui.locked, ui.error, ui.resumeAt, ui.nextEp, ui.showJump, vm.playMode) {
        if (ui.overlay && ui.sheet == null && !ui.panel && !ui.locked && ui.error == null &&
            ui.resumeAt <= 0L && !ui.showJump && ui.nextEp == null
        ) {
            delay(if (dev.tv) 7_000L else 3_500L)
            if (ui.overlay && ui.sheet == null && !ui.panel && !ui.locked && ui.error == null &&
                ui.resumeAt <= 0L && !ui.showJump && ui.nextEp == null && !(vod && !player.playWhenReady)
            ) ui.overlay = false
        }
    }

    LaunchedEffect(ui.overlay, ui.sheet, ui.panel, dev.tv) {
        if (ui.overlay && ui.sheet == null && !ui.panel && dev.tv) runCatching { playFocus.requestFocus() }
    }

    LaunchedEffect(ui.nextEp) {
        val target = ui.nextEp ?: return@LaunchedEffect
        if (!store.autoNext || ui.sleepEnd) return@LaunchedEffect
        delay(5_000)
        if (ui.nextEp == target) { ui.nextEp = null; vm.playNext() }
    }

    LaunchedEffect(Unit) {
        while (true) { delay(30_000); vm.tickWatch(30) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            if (vm.sleepAt > 0 && System.currentTimeMillis() >= vm.sleepAt) {
                vm.sleepAt = 0L
                vm.stop()
                break
            }
        }
    }

    KeepScreenOn()

    BackHandler {
        when {
            ui.sheet != null -> { ui.sheet = null; ui.sheetRev++; runCatching { rootFocus.requestFocus() } }
            ui.showJump -> ui.showJump = false
            ui.panel -> { ui.panel = false; runCatching { rootFocus.requestFocus() } }
            ui.resumeAt > 0 -> { ui.resumeAt = -1L; player.seekTo(0L); player.playWhenReady = true }
            ui.error != null -> vm.stop()
            ui.nextEp != null -> ui.nextEp = null
            ui.ended -> { ui.ended = false; actions.restart() }
            ui.locked -> { ui.lockHint = true; hud("🔒 الشاشة مقفولة") }
            else -> vm.stop()
        }
    }

    /* ------------------------------------------------------------------ */
    /* TV remote                                                          */
    /* ------------------------------------------------------------------ */

    fun handleRemoteKey(ev: KeyEvent): Boolean {
        if (ev.type != KeyEventType.KeyDown) return false
        if (ev.key == Key.Back) return false
        if (ui.sheet != null || ui.panel || ui.showJump || ui.resumeAt > 0 || ui.error != null) return false

        when (ev.key) {
            Key.MediaPlayPause -> { actions.togglePlay(); return true }
            Key.MediaPlay -> { actions.play(); return true }
            Key.MediaPause -> { actions.pause(); return true }
            Key.MediaFastForward -> { actions.seekBy(30_000L); return true }
            Key.MediaRewind -> { actions.seekBy(-30_000L); return true }
            Key.MediaNext -> { actions.nextVideo(); return true }
            Key.MediaPrevious -> { actions.prevVideo(); return true }
        }

        val digit = when (ev.key) {
            Key.Zero, Key.NumPad0 -> '0'; Key.One, Key.NumPad1 -> '1'; Key.Two, Key.NumPad2 -> '2'
            Key.Three, Key.NumPad3 -> '3'; Key.Four, Key.NumPad4 -> '4'; Key.Five, Key.NumPad5 -> '5'
            Key.Six, Key.NumPad6 -> '6'; Key.Seven, Key.NumPad7 -> '7'; Key.Eight, Key.NumPad8 -> '8'
            Key.Nine, Key.NumPad9 -> '9'; else -> null
        }
        if (digit != null && live && vm.zapEnabled) {
            if (ui.digits.length < 4) ui.digits += digit
            ui.overlay = true; ui.ping++
            return true
        }

        if (ui.locked) {
            if (ev.key in setOf(Key.DirectionCenter, Key.Enter, Key.DirectionLeft, Key.DirectionRight,
                    Key.DirectionUp, Key.DirectionDown, Key.Menu, Key.Info)
            ) {
                ui.lockHint = true; ui.ping++
                return true
            }
            return false
        }

        // With controls visible, arrows move focus between focusable buttons / the timeline.
        if (ui.overlay && dev.tv) return false

        fun scrub(direction: Int) {
            val base = ui.seekInterval.coerceAtLeast(1) * 1_000L
            val multiplier = when {
                ev.nativeKeyEvent.repeatCount < 4 -> 1
                ev.nativeKeyEvent.repeatCount < 12 -> 3
                ev.nativeKeyEvent.repeatCount < 30 -> 6
                else -> 12
            }
            actions.seekBy(direction * base * multiplier)
        }

        return when (ev.key) {
            Key.DirectionLeft -> {
                if (live && !player.isCurrentMediaItemSeekable) ui.panel = true else scrub(-1)
                ui.ping++; true
            }
            Key.DirectionRight -> {
                if (live && !player.isCurrentMediaItemSeekable) vm.toggleFav(chRef.value) else scrub(1)
                ui.ping++; true
            }
            Key.DirectionUp, Key.DirectionDown -> {
                val dir = if (ev.key == Key.DirectionUp) 1 else -1
                when (UpDownAction.of(store.tvUpDown)) {
                    UpDownAction.Volume -> {
                        am.stepVolume(dir > 0)
                        ui.volume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                        hud("🔊 ${ui.volume}/${ui.volumeMax}")
                    }
                    UpDownAction.Menu -> openSheet(PlayerTab.Playback)
                    UpDownAction.Channels -> if (vm.hasNeighbor(dir)) vm.playNeighbor(dir) else Unit
                }
                ui.ping++; true
            }
            Key.DirectionCenter, Key.Enter -> {
                if (showSkip) { player.seekTo(introEnd); hud("⏭ تم تخطي المقدمة") }
                else actions.togglePlay()
                true
            }
            Key.Menu, Key.Info, Key.M -> { openSheet(PlayerTab.Playback); true }
            Key.ChannelUp -> { if (vm.zapEnabled) { vm.zap(1); true } else false }
            Key.ChannelDown -> { if (vm.zapEnabled) { vm.zap(-1); true } else false }
            else -> false
        }
    }

    /* ------------------------------------------------------------------ */
    /* UI                                                                 */
    /* ------------------------------------------------------------------ */

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { handleRemoteKey(it) }
    ) {
        // Video layer: gestures + zoom/pan/rotation transform. No UI state is read as a key here.
        val rotationScale = if (ui.rotation % 180 != 0 && ui.surfaceW > 0 && ui.surfaceH > 0)
            minOf(ui.surfaceW, ui.surfaceH).toFloat() / maxOf(ui.surfaceW, ui.surfaceH) else 1f
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = ui.zoom * rotationScale
                    scaleY = ui.zoom * rotationScale
                    translationX = ui.panX
                    translationY = ui.panY
                    rotationZ = ui.rotation.toFloat()
                }
                .then(
                    if (!ui.locked && !vm.inPip && store.gesturesEnabled) {
                        Modifier.playerGesturesV2(
                            key = listOf(ch.url, epoch, ui.aspect, live),
                            enabled = true,
                            seekEnabled = store.gestureSeek,
                            brightnessEnabled = store.gestureBrightness,
                            volumeEnabled = store.gestureVolume,
                            doubleTapEnabled = store.gestureDoubleTap,
                            pinchEnabled = store.gesturePinch,
                            panEnabled = store.gesturePan,
                            sensitivity = store.gestureSensitivity,
                            canSeek = { player.isCurrentMediaItemSeekable || player.duration > 0 },
                            duration = { ui.duration },
                            position = { player.currentPosition },
                            zoomed = { ui.zoom > 1.01f },
                            onTap = { ui.overlay = !ui.overlay; ui.ping++ },
                            onDoubleTap = { zone ->
                                if (zone == 0) actions.togglePlay()
                                else actions.seekBy(zone * ui.seekInterval * 1_000L)
                            },
                            onLongPress = { openSheet(PlayerTab.Playback) },
                            onSeekStart = {
                                feedback.kind = GestureKind.Seek
                                feedback.seekTarget = player.currentPosition
                                feedback.active = true
                            },
                            onSeekPreview = { target, delta ->
                                feedback.kind = GestureKind.Seek
                                feedback.seekTarget = target
                                feedback.seekDelta = delta
                                feedback.active = true
                            },
                            onSeekCommit = { target ->
                                player.seekTo(target)
                                ui.position = target
                                hud((if (target >= player.currentPosition) "⏩ " else "⏪ ") + fmtTime(target))
                                feedback.clear()
                            },
                            onBrightness = { delta ->
                                applyBrightness(ui.brightness + delta)
                                feedback.kind = GestureKind.Brightness
                                feedback.progress = ui.brightness
                                feedback.active = true
                            },
                            onVolume = { delta ->
                                applyVolumeDelta(delta)
                                feedback.kind = GestureKind.Volume
                                feedback.progress = ui.volume.toFloat() / ui.volumeMax.coerceAtLeast(1)
                                feedback.active = true
                            },
                            onZoom = { ratio, pan ->
                                val z = (ui.zoom * ratio).coerceIn(1f, 4f)
                                ui.zoom = z
                                val mx = (z - 1f) * (view?.width ?: 0) / 2f
                                val my = (z - 1f) * (view?.height ?: 0) / 2f
                                ui.panX = (ui.panX + pan.x).coerceIn(-mx, mx)
                                ui.panY = (ui.panY + pan.y).coerceIn(-my, my)
                                feedback.kind = GestureKind.Zoom
                                feedback.progress = (z - 1f) / 3f
                                feedback.active = true
                            },
                            onPan = { delta ->
                                val mx = (ui.zoom - 1f) * (view?.width ?: 0) / 2f
                                val my = (ui.zoom - 1f) * (view?.height ?: 0) / 2f
                                ui.panX = (ui.panX + delta.x).coerceIn(-mx, mx)
                                ui.panY = (ui.panY + delta.y).coerceIn(-my, my)
                                feedback.kind = GestureKind.Pan
                                feedback.active = true
                            },
                            onGestureEnd = { feedback.clear() }
                        )
                    } else Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { ui.overlay = !ui.overlay; ui.ping++ })
                    }
                )
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { ui.surfaceW = it.width; ui.surfaceH = it.height }
            ) {
                val forced = ui.aspect.forcedRatio
                var boxW = maxWidth
                var boxH = maxHeight
                if (forced != null) {
                    if (boxW / boxH > forced) boxW = boxH * forced else boxH = boxW / forced
                }
                val resizeMode = when {
                    forced != null -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    ui.aspect == AspectMode.Smart -> smartMode(ui.videoAr, ui.surfaceW, ui.surfaceH)
                    else -> ui.aspect.resizeMode
                }
                AndroidView(
                    modifier = Modifier.size(boxW, boxH),
                    factory = { c ->
                        PlayerView(c).apply {
                            useController = false
                            this.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            this.player = player
                            view = this
                        }
                    },
                    update = {
                        it.player = player
                        it.useController = false
                        it.resizeMode = resizeMode
                        view = it
                    }
                )
            }
        }

        // External subtitles are drawn by our own renderer, below the controls.
        key(ui.settingsRev) { ExternalSubtitleOverlay(ui, store, Modifier.fillMaxSize()) }

        if (ui.locked && !vm.inPip) {
            Box(Modifier.fillMaxSize().clickable { ui.lockHint = true; ui.ping++ })
            Row(
                Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(20.dp))
                    .clickable { actions.toggleLock() }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("🔒 ${if (ui.lockHint) "اضغط لفتح القفل" else "الشاشة مقفولة"}", color = Color.White, fontSize = 15.sp)
                PlayerRoundButton("🔓", size = 34) { actions.toggleLock() }
            }
        }

        if (!vm.inPip && ui.digits.isNotEmpty()) {
            Text(
                ui.digits, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp)
            )
        }

        if (showSkip && !vm.inPip && !ui.locked) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(horizontal = 24.dp, vertical = 150.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                    .clickable { player.seekTo(introEnd); hud("⏭ تم تخطي المقدمة") }
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) { Text("⏭ تخطي المقدمة  (OK)", color = Accent, fontSize = 15.sp) }
        }

        GestureHud(feedback, Modifier.align(Alignment.Center))
        if (ui.hudText.isNotEmpty() && !feedback.active && !vm.inPip) {
            Text(
                ui.hudText, fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).background(Color(0xBB000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }

        if (ui.status == "جارٍ التحميل..." && !vm.inPip && ui.error == null) {
            androidx.compose.material3.CircularProgressIndicator(
                Modifier.align(Alignment.Center).size(46.dp), color = Accent, strokeWidth = 3.dp
            )
        } else if (ui.status.isNotEmpty() && !vm.inPip && ui.error == null) {
            Text(
                ui.status, color = Color.White, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 90.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(12.dp)
            )
        }

        if (ui.stats && !vm.inPip) {
            Text(
                ui.infoText, fontSize = 11.sp, color = Accent,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(8.dp)
            )
        }

        // Top bar
        if (ui.overlay && !ui.locked && !vm.inPip && ui.sheet == null) {
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
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
                    Text("🔴 مباشر", fontSize = 12.sp, color = Color(0xFFFF8A8F),
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

        // Bottom control bar
        if (ui.overlay && !ui.locked && !vm.inPip && ui.sheet == null && ui.error == null && ui.resumeAt <= 0L) {
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
                                    "${hm(now.start)} - ${hm(now.stop)}" + (next?.let { " · التالي: ${it.title}" } ?: ""),
                                    fontSize = 11.sp, color = Color(0xFFB8C2CF), maxLines = 1
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
                        PlayerButton("☰ القنوات") { actions.openChannels() }
                        PlayerButton("⏪ 30") { rewindLive(vm, player, chRef.value.url, 30); ui.ping++ }
                        if (player.isCurrentMediaItemLive && player.currentLiveOffset > 8_000L) {
                            PlayerButton("🔴 مباشر") { player.seekToDefaultPosition(); ui.ping++ }
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
                    PlayerButton("💬 ترجمة", active = ui.subOn) { openSheet(PlayerTab.Subtitles) }
                    PlayerButton("🎧 صوت", active = store.audioBoost) { openSheet(PlayerTab.Audio) }
                    PlayerButton(if (ui.speed == 1f) "⏱ السرعة" else "⏱ ${ui.speed}x", active = ui.speed != 1f) { openSheet(PlayerTab.Playback) }
                    PlayerButton("⛶ ${ui.aspect.label.substringBefore(' ')}") { openSheet(PlayerTab.Display) }
                    PlayerButton("🔁", active = ui.loop) { actions.setLoop(!ui.loop) }
                    if (dev.touch) PlayerButton("🔒 قفل") { actions.toggleLock() }
                    PlayerButton("⚙ خيارات") { openSheet(PlayerTab.Playback) }
                    PlayerButton("📊") { ui.stats = !ui.stats }
                }
            }
        }

        if (ui.panel && live && !vm.inPip) {
            ChannelPanel(vm, chRef.value, { ui.panel = false; runCatching { rootFocus.requestFocus() } }, Modifier.align(Alignment.CenterStart))
        }

        ui.nextEp?.let { ep ->
            Column(
                Modifier.align(Alignment.BottomEnd).padding(24.dp)
                    .background(Color(0xEE101522), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(16.dp)).padding(16.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    if (store.autoNext && !ui.sleepEnd) "الحلقة التالية خلال 5 ثوانٍ: ${ep.num}"
                    else "الحلقة التالية: ${ep.num}",
                    color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MBtn("تشغيل الآن") { ui.nextEp = null; vm.playNext() }
                    MBtn("إلغاء", filled = false) { ui.nextEp = null }
                }
            }
        }

        if (ui.ended && !vm.inPip) {
            Column(
                Modifier.align(Alignment.Center).background(Color(0xEE101522), RoundedCornerShape(18.dp))
                    .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(18.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("انتهى المحتوى", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MBtn("إعادة") { actions.restart() }
                    if (vm.hasNextEpisode(chRef.value)) MBtn("الحلقة التالية") { vm.playNext() }
                    MBtn("خروج", filled = false) { vm.stop() }
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
                            vm.say("سيظهر زر تخطي المقدمة في حلقات هذا المسلسل")
                        }
                    },
                    onClearIntro = { seriesKey?.let { vm.store.setIntroEnd(it, 0L); vm.say("تم مسح المقدمة") } },
                    onRewindLive = { sec -> rewindLive(vm, player, chRef.value.url, sec) },
                    onLiveEdge = { player.seekToDefaultPosition(); player.playWhenReady = true; ui.ping++ },
                    onAlternatives = {
                        val alts = vm.alternativesOf(chRef.value)
                        if (alts.isEmpty()) vm.say("لا توجد مصادر بديلة لهذه القناة")
                        else vm.menu = com.nabd.tv.MenuRequest(
                            "مصادر بديلة",
                            alts.map { a -> (a.name + " · " + vm.health.state(a.url).name) to { vm.useAlternative(a) } }
                        )
                    }
                )
            }
        }
    }
}

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

/** Marker for "buffering / loading": drives the spinner, is never shown as text. */
const val STATUS_LOADING = "\u0000loading"

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
    // Automatic opening window (AniSkip for anime, TheIntroDB for movies / other series). A manually saved intro end still wins.
    var aniIntro by remember(ch.url) { mutableStateOf<AniIntro?>(null) }
    val manualSkip = introEnd > 0 && ui.position < introEnd - 1_500L && vod
    val autoSkip = !manualSkip && vod &&
        aniIntro?.let { ui.position >= it.startMs - 2_000L && ui.position < it.endMs - 1_500L } == true
    val showSkip = manualSkip || autoSkip
    val skipTarget = if (manualSkip) introEnd else (aniIntro?.endMs ?: 0L)
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
    val timelineFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val endFocus = remember { FocusRequester() }

    var popup by remember { mutableStateOf<PopupKind?>(null) }
    var popupSub by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.overlay) { if (!ui.overlay) { popup = null; popupSub = null } }
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

    fun applyExternalSubtitle(copied: ExternalSubtitle) {
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
            if (res.cues.isEmpty()) res.error ?: S(R.string.a_1122e8b1)
            else S(R.string.x_subs_loaded, res.cues.size)
        )
    }

    val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val copied = SubtitleFiles.copy(ctx, uri, chRef.value.url)
        if (copied == null) vm.say(S(R.string.a_6972e282)) else applyExternalSubtitle(copied)
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
                ui.ping++   // no centre-screen time text; the timeline shows the position
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
                player.seekTo(target); ui.position = target; ui.ping++
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
            setLoop = { on -> ui.loop = on; ui.settingsRev++; hud(if (on) S(R.string.a_4fcdcb8e) else S(R.string.a_33dc780a)) },
            setPointA = {
                ui.abStart = player.currentPosition
                if (ui.abEnd <= ui.abStart) ui.abEnd = -1L
                hud("A · ${fmtTime(ui.abStart)}")
            },
            setPointB = {
                if (ui.abStart < 0) vm.say(S(R.string.a_da7f36ee))
                else if (player.currentPosition <= ui.abStart) vm.say(S(R.string.a_5bce9aef))
                else { ui.abEnd = player.currentPosition; hud("B · ${fmtTime(ui.abEnd)}") }
            },
            clearAb = { ui.abStart = -1L; ui.abEnd = -1L; hud(S(R.string.a_3acb3f5d)) },
            setSleep = { minutes ->
                if (minutes <= 0) { vm.sleepAt = 0L; ui.sleepEnd = false; vm.say(S(R.string.a_c125a5f3)) }
                else {
                    vm.sleepAt = System.currentTimeMillis() + minutes * 60_000L
                    ui.sleepEnd = false
                    vm.say(S(R.string.x_sleep_after, minutes))
                }
                ui.settingsRev++
            },
            setSleepEnd = { ui.sleepEnd = true; vm.sleepAt = 0L; vm.say(S(R.string.a_3771c6cb)); ui.settingsRev++ },
            cancelSleep = { ui.sleepEnd = false; vm.sleepAt = 0L; vm.say(S(R.string.a_c125a5f3)); ui.settingsRev++ },
            toggleLock = {
                ui.locked = !ui.locked
                ui.overlay = !ui.locked
                ui.lockHint = ui.locked
                ui.ping++
            },
            enterPip = {
                val ok = (act as? MainActivity)?.enterPip() == true
                if (!ok) vm.say(S(R.string.a_31203a04))
            },
            hideControls = { ui.overlay = false },
            openChannels = { if (live) { ui.panel = true; ui.ping++ } else vm.say(S(R.string.a_115f09a4)) },
            loadSubtitle = { subPicker.launch(arrayOf("*/*")) },
            removeSubtitle = { clearSubtitleFile(); vm.say(S(R.string.a_0b2e99bd)) },
            selectSubtitle = { index ->
                val track = ui.textTracks.getOrNull(index) ?: return@PlayerActions
                if (ui.extSub != null) clearSubtitleFile()
                player.selectTrack(C.TRACK_TYPE_TEXT, track)
                ui.subOn = true
                store.subEnabled = true
                player.setSubtitlesEnabled(true)
                seriesKey?.let { store.setSeriesSubLang(it, track.language) }   // remembered for the next episodes
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
                vm.say(if (enc == "auto") S(R.string.a_6316a65b) else S(R.string.x_enc_changed, enc))
            },
            setSubtitleDelay = { ms ->
                store.subDelayMs = ms.coerceIn(-10_000, 10_000)
                ui.settingsRev++
                hud("💬 ${store.subDelayMs} ms")
            },
            setSubtitlesEnabled = { on ->
                ui.subOn = on
                store.subEnabled = on
                seriesKey?.let { k -> if (!on) store.setSeriesSubLang(k, "off") else if (store.seriesSubLang(k) == "off") store.setSeriesSubLang(k, "") }
                if (on && ui.extSub == null) player.setSubtitlesEnabled(true)
                else if (!on) player.setSubtitlesEnabled(false)
                ui.settingsRev++
            },
            setAudioTrack = { track ->
                player.selectTrack(C.TRACK_TYPE_AUDIO, track); ui.tracksRev++
                val saved = seriesKey != null && track.language.isNotBlank()
                if (saved) store.setSeriesAudioLang(seriesKey!!, track.language)   // remembered for the next episodes
                hud("🔊 ${track.label}" + if (saved) S(R.string.a_53712cf7) else "")
            },
            clearAudioOverride = {
                player.clearTrackOverride(C.TRACK_TYPE_AUDIO)
                seriesKey?.let { store.setSeriesAudioLang(it, "") }
                hud(S(R.string.a_410e1499))
            },
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
                ui.status = S(R.string.a_f0a1bbc7)
                epoch++
                ui.ping++
            },
            retry = {
                retries = 0; altUsed = false
                ui.error = null; ui.errorDetail = ""; ui.status = STATUS_LOADING
                player.prepare(); player.playWhenReady = true
                ui.ping++
            },
            openExternal = {
                runCatching {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(chRef.value.url)).setType("video/*")
                    ctx.startActivity(Intent.createChooser(i, S(R.string.a_a332848d)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { vm.say(S(R.string.a_34286b07)) }
            },
            interact = { ui.overlay = true; ui.ping++ },
            changed = { ui.settingsRev++ },
            useSubtitle = { applyExternalSubtitle(it) }
        )
    }

    val seriesRef = rememberUpdatedState(seriesKey)
    val prefApplied = remember { arrayOf("", "") }   // media url the saved audio / subtitle choice was applied to

    /** Re-applies the audio / subtitle language the user picked earlier for this series (once per episode). */
    fun applySeriesTrackPrefs() {
        val sk = seriesRef.value ?: return
        val url = chRef.value.url
        if (prefApplied[0] != url && ui.audioTracks.isNotEmpty()) {
            prefApplied[0] = url
            val al = store.seriesAudioLang(sk)
            if (al.isNotBlank()) ui.audioTracks.firstOrNull { sameLang(it.language, al) }
                ?.let { if (!it.selected) player.selectTrack(C.TRACK_TYPE_AUDIO, it) }
            if (store.seriesSubLang(sk) == "off" && ui.extSub == null) { player.setSubtitlesEnabled(false); ui.subOn = false }
        }
        if (prefApplied[1] != url && ui.textTracks.isNotEmpty()) {
            val sl = store.seriesSubLang(sk)
            if (sl.isNotBlank() && sl != "off" && ui.extSub == null) {
                prefApplied[1] = url
                ui.textTracks.firstOrNull { sameLang(it.language, sl) }?.let {
                    if (!it.selected) player.selectTrack(C.TRACK_TYPE_TEXT, it)
                    player.setSubtitlesEnabled(true); ui.subOn = true
                }
            }
        }
    }

    fun refreshTracks() {
        ui.audioTracks = player.audioTracks()
        ui.textTracks = player.textTracks()
        applySeriesTrackPrefs()
        ui.tracksRev++
        // Silent video: the file has audio but no track could be selected (codec the device cannot decode).
        val ag = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
        if (ag.isNotEmpty() && ag.none { it.isSelected }) {
            val f = runCatching { ag.first().getTrackFormat(0) }.getOrNull()
            hud(S(R.string.a_ff88db6d) + (f?.sampleMimeType?.substringAfter('/') ?: f?.codecs ?: "?") + "))")
        }
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

    LaunchedEffect(ch.url) {
        ui.zoom = 1f; ui.panX = 0f; ui.panY = 0f; ui.rotation = 0
        ui.abStart = -1L; ui.abEnd = -1L
    }

    // Automatic intro window. Anime episodes: AniSkip first; other series and movies: TheIntroDB (needs the TMDB key).
    // Silent on any failure, and a manually saved intro end still takes priority.
    LaunchedEffect(ch.url, seriesKey) {
        aniIntro = null
        if (!vod) return@LaunchedEffect
        val http = vm.repo.http
        if (seriesKey != null) {
            val ep = vm.episodes.firstOrNull { it.url == ch.url } ?: return@LaunchedEffect
            val name = vm.detail?.name ?: return@LaunchedEffect
            aniIntro = AniSkip.intro(http, ctx, name, ep.season, ep.num)
                ?: TheIntroDb.intro(http, ctx, store.tmdbKey, name, true, ep.season, ep.num)
        } else if (kindOf(ch) == Kind.Movie) {
            aniIntro = TheIntroDb.intro(http, ctx, store.tmdbKey, ch.name, false, null, null)
        }
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
        val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = refreshTracks()

            override fun onVideoSizeChanged(size: VideoSize) {
                if (store.afrMode == "switch") act?.let { runCatching { matchDisplayRefreshRate(it, player) } }
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
                        // Retrying at once hammers a provider that is already refusing (503 / connection limit): back off.
                        val wait = if (code != null) retries * 2_000L else 0L
                        if (wait > 0) ui.status = S(R.string.a_1e15c4e2)
                        retryHandler.postDelayed({ runCatching { player.prepare() } }, wait)
                    }
                    !altUsed && alternate != null -> {
                        altUsed = true; retries = 0
                        player.setMediaItem(mediaItemFor(alternate)); player.prepare(); player.playWhenReady = true
                        ui.status = S(R.string.a_64ba4488)
                    }
                    else -> {
                        vm.health.markFailure(url)
                        val showError = {
                            ui.error = S(R.string.a_ade6c5da)
                            if (code == 503 || code == 429) ui.error = S(R.string.a_8ebc053f)
                            ui.errorDetail = (code?.let { "HTTP $it" } ?: error.errorCodeName) +
                                (error.cause?.message?.let { " · $it" } ?: "")
                            ui.status = ""
                        }
                        if (live && vm.failover()) ui.status = S(R.string.a_fb546072)
                        else if (!live && vm.tryVodFailover(showError)) ui.status = S(R.string.x_vod_failover)
                        else showError()
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> ui.status = STATUS_LOADING
                    Player.STATE_READY -> {
                        ui.status = ""
                        retries = 0
                        if (pendingResume > 0) {
                            if (player.duration > pendingResume + 5_000L) {
                                player.playWhenReady = false
                                ui.resumeAt = pendingResume
                            } else player.playWhenReady = true   // nothing to ask about: start playing instead of staying paused
                            pendingResume = -1L
                        }
                        if (LiveRewind.pending > 0) {
                            val seconds = LiveRewind.pending
                            LiveRewind.pending = 0
                            if (player.isCurrentMediaItemSeekable) player.seekTo((player.currentPosition - seconds * 1000L).coerceAtLeast(0L))
                            else vm.say(S(R.string.a_8aa1f737))
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
                            ui.status = S(R.string.a_59bb3584)
                        }
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose { retryHandler.removeCallbacksAndMessages(null); player.removeListener(listener) }
    }

    // Each engine rebuild creates a new ExoPlayer; the old one must be released (audio focus, decoder, network).
    DisposableEffect(player) {
        onDispose { runCatching { player.playWhenReady = false; player.release() } }   // silence at once, even if release waits on the network
    }

    LaunchedEffect(ch.url, player) {
        retries = 0; altUsed = false
        ui.error = null; ui.errorDetail = ""; ui.nextEp = null; ui.ended = false
        ui.hudText = ""; ui.resumeAt = -1L; ui.status = STATUS_LOADING
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
            ui.wantPlay = player.playWhenReady
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
            act?.let { runCatching { resetDisplayMode(it) } }
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
        val outgoing = ch   // captured now: chRef already points at the next episode when this runs
        onDispose {
            if (store.rememberPosition && vm.playMode == PlayMode.Vod && ui.duration > 0 && ui.resumeAt <= 0L && ui.error == null)
                vm.saveProgress(outgoing, ui.position, ui.duration)
        }
    }

    LaunchedEffect(ch.url, player) {
        while (true) {
            delay(10_000)
            if (store.rememberPosition && vm.playMode == PlayMode.Vod && ui.duration > 0 && ui.resumeAt <= 0L && ui.error == null)
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
            delay(if (dev.tv) 7_000L else if (vod) 5_000L else 3_500L)
            if (ui.overlay && ui.sheet == null && !ui.panel && !ui.locked && ui.error == null &&
                ui.resumeAt <= 0L && !ui.showJump && ui.nextEp == null && !(vod && !player.playWhenReady)
            ) ui.overlay = false
        }
    }

    LaunchedEffect(ui.overlay, ui.sheet, ui.panel, dev.tv, ui.duration > 0) {
        if (ui.overlay && ui.sheet == null && !ui.panel && !ui.locked && dev.tv) {
            delay(60)
            // Movies / episodes: land on the timeline so Left/Right scrub immediately.
            runCatching { if ((vod || live) && ui.duration > 0) timelineFocus.requestFocus() else playFocus.requestFocus() }
        } else if (!ui.overlay && ui.sheet == null && !ui.panel && ui.nextEp == null && !ui.ended && ui.resumeAt <= 0L && ui.error == null) {
            // Controls hid while a button had focus: hand the keys back to the player.
            runCatching { rootFocus.requestFocus() }
        }
    }
    LaunchedEffect(ui.nextEp) { if (ui.nextEp != null) { delay(80); runCatching { nextFocus.requestFocus() } } }
    LaunchedEffect(ui.ended) { if (ui.ended) { delay(80); runCatching { endFocus.requestFocus() } } }

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
            // One press closes ONE open layer; with nothing open it leaves. The resume prompt, the next-episode card,
            // the end card and error card no longer swallow Back (Back used to restart the movie / replay / cancel).
            ui.sheet != null -> { ui.sheet = null; ui.sheetRev++; runCatching { rootFocus.requestFocus() } }
            ui.showJump -> ui.showJump = false
            ui.panel -> { ui.panel = false; runCatching { rootFocus.requestFocus() } }
            ui.locked -> { ui.lockHint = true; hud(S(R.string.a_8d3dacb8)) }
            vod && ui.overlay && dev.tv -> { ui.overlay = false; runCatching { rootFocus.requestFocus() } }
            else -> vm.stop()
        }
    }

    /* ------------------------------------------------------------------ */
    /* TV remote                                                          */
    /* ------------------------------------------------------------------ */

    fun handleRemoteKey(ev: KeyEvent): Boolean {
        if (ev.type != KeyEventType.KeyDown) return false
        if (ev.key == Key.Back) return false
        if (ui.sheet != null || ui.panel || ui.showJump || ui.resumeAt > 0 || ui.error != null || ui.ended || ui.nextEp != null) return false

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
        if (ui.overlay && (dev.tv || vod)) { if (vod) ui.ping++; return false }

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

        if (vod) {
            // Controls hidden: ANY arrow brings up the timeline (Left/Right also move through the film).
            when (ev.key) {
                Key.DirectionLeft -> { scrub(-1); ui.overlay = true; ui.ping++; return true }
                Key.DirectionRight -> { scrub(1); ui.overlay = true; ui.ping++; return true }
                Key.DirectionUp, Key.DirectionDown -> { ui.overlay = true; ui.ping++; return true }
                Key.DirectionCenter, Key.Enter -> {
                    if (showSkip) { player.seekTo(skipTarget); hud(S(R.string.a_53d04dc8)) } else actions.togglePlay()
                    ui.overlay = true; ui.ping++; return true
                }
                else -> {}
            }
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
                if (showSkip) { player.seekTo(skipTarget); hud(S(R.string.a_53d04dc8)) }
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
                Modifier.align(Alignment.Center).background(OverlayScrimStrong, RoundedCornerShape(20.dp))
                    .clickable { actions.toggleLock() }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("🔒 ${S(if (ui.lockHint) R.string.x_lock_hint else R.string.x_locked)}", color = Color.White, fontSize = 15.sp)
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
                    .background(OverlayScrimStrong, RoundedCornerShape(12.dp))
                    .clickable { player.seekTo(skipTarget); hud(S(R.string.a_53d04dc8)) }
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) { Text(S(R.string.a_0b73fbca), color = Accent, fontSize = 15.sp) }
        }

        GestureHud(feedback, Modifier.align(Alignment.Center))
        if (ui.hudText.isNotEmpty() && !feedback.active && !vm.inPip) {
            Text(
                ui.hudText, fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).background(Color(0xBB000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }

        if (ui.status == STATUS_LOADING && !vm.inPip && ui.error == null) {
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
                ui.infoText, fontSize = 12.sp, color = Accent,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(8.dp)
            )
        }

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

        if (ui.panel && live && !vm.inPip) {
            ChannelPanel(vm, chRef.value, { ui.panel = false; runCatching { rootFocus.requestFocus() } }, Modifier.align(Alignment.CenterStart))
        }

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
}

/** True when two language codes are the same language (handles "ar" vs "ara", "en" vs "eng"). */
private fun sameLang(a: String, b: String): Boolean {
    if (a.isBlank() || b.isBlank()) return false
    fun n(x: String) = runCatching { java.util.Locale(x.substringBefore('-').lowercase()).isO3Language }.getOrDefault(x.lowercase())
    return n(a) == n(b)
}

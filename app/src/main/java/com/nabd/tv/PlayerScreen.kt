package com.nabd.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
fun buildPlayer(ctx: android.content.Context, store: Store, live: Boolean): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent(httpUa)
        .setConnectTimeoutMs(10_000).setReadTimeoutMs(10_000)
    // Live: small buffers = fast channel start / fast zapping. VOD: larger buffer for smooth seeking.
    val load = (if (live) DefaultLoadControl.Builder().setBufferDurationsMs(3000, 20000, 1000, 2000)
    else DefaultLoadControl.Builder().setBufferDurationsMs(15000, 60000, 2500, 5000)).build()
    // Hardware decoders first (default), with software fallback + decoder fallback on init failure.
    val renderers = DefaultRenderersFactory(ctx)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
    val p = ExoPlayer.Builder(ctx, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(DefaultDataSource.Factory(ctx, http)))
        .setLoadControl(load).build()
    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
        .setPreferredAudioLanguage(store.prefAudioLang)
        .setPreferredTextLanguage(store.prefSubLang)
        .build()
    return p
}

private val ratios = listOf(
    "ملاءمة" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
    "ملء الشاشة" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    "تمديد" to AspectRatioFrameLayout.RESIZE_MODE_FILL
)

@OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)
@Composable
fun PlayerScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val ch = vm.current ?: return
    val live = vm.playMode == PlayMode.Live
    val vod = vm.playMode == PlayMode.Vod
    val player = remember { buildPlayer(ctx, vm.store, live) }
    var view by remember { mutableStateOf<PlayerView?>(null) }

    var retries by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var ping by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf(true) }
    var alt by remember { mutableStateOf(false) }
    var ratio by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    var digits by remember { mutableStateOf("") }
    var startedAt by remember { mutableLongStateOf(0L) }
    var nextEp by remember { mutableStateOf<Episode?>(null) }
    val chUrl by rememberUpdatedState(ch.url)
    val chRef by rememberUpdatedState(ch)

    // Save resume position when leaving, and every 15 s while a VOD plays.
    DisposableEffect(Unit) {
        onDispose {
            if (vod) vm.saveProgress(chRef, player.currentPosition, player.duration.takeIf { it > 0 } ?: 0L)
            vm.health.flush()
            player.release()
        }
    }
    LaunchedEffect(vod, ch.url) {
        while (vod) { delay(15_000); vm.saveProgress(chRef, player.currentPosition, player.duration.takeIf { it > 0 } ?: 0L) }
    }

    LaunchedEffect(ch.url) {
        retries = 0; status = ""; alt = false; nextEp = null; speed = 1f; player.setPlaybackSpeed(1f)
        startedAt = System.currentTimeMillis()
        player.setMediaItem(MediaItem.fromUri(ch.url), if (vm.resumeAt > 0) vm.resumeAt else C.TIME_UNSET)
        player.prepare(); player.playWhenReady = true
        vm.noteStart(ch)
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                val code = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                val u = chUrl
                val altUrl = when {
                    u.endsWith(".ts") -> u.removeSuffix(".ts") + ".m3u8"
                    u.endsWith(".m3u8") -> u.removeSuffix(".m3u8") + ".ts"
                    else -> null
                }
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare()
                } else if (retries < 2 && (code == null || code >= 500)) {
                    retries++; player.prepare()
                } else if (!alt && altUrl != null) {
                    alt = true; retries = 0
                    player.setMediaItem(MediaItem.fromUri(altUrl)); player.prepare()
                } else {
                    vm.health.markFailure(u)
                    // Smart failover: jump to a healthy duplicate of the same channel.
                    if (live && vm.failover()) { status = "تحويل تلقائي لمصدر بديل..." }
                    else status = "تعذّر التشغيل" + (code?.let { " (HTTP $it)" } ?: " (${e.errorCodeName})")
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> status = "جارٍ التحميل..."
                    Player.STATE_READY -> {
                        status = ""; retries = 0
                        if (live) vm.health.markSuccess(chUrl, System.currentTimeMillis() - startedAt)
                        // Pick preferred subtitles if the stream offers them (preferred language is set on the selector).
                    }
                    Player.STATE_ENDED -> if (vod) {
                        vm.saveProgress(chRef, player.duration, player.duration)
                        val n = vm.nextEpisodeOf(chRef)
                        if (n != null) nextEp = n
                    }
                }
            }
        }
        player.addListener(l); onDispose { player.removeListener(l) }
    }

    // Auto-play the next episode after a short countdown.
    LaunchedEffect(nextEp) {
        if (nextEp != null) { delay(5000); if (nextEp != null) vm.playNext() }
    }

    LaunchedEffect(ch.url, ping) { overlay = true; delay(3500); overlay = false }

    // Digits typed on the remote → jump to that channel number after a short pause.
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) {
            delay(1500)
            digits.toIntOrNull()?.let { vm.zapToNumber(it) }
            digits = ""
        }
    }

    // Subtitle appearance (size, colour, background) from settings.
    LaunchedEffect(view) {
        view?.subtitleView?.apply {
            setFractionalTextSize(vm.store.subSize)
            setStyle(
                CaptionStyleCompat(
                    vm.store.subColor, if (vm.store.subBackground) 0xAA000000.toInt() else 0,
                    0, CaptionStyleCompat.EDGE_TYPE_OUTLINE, 0xFF000000.toInt(), null
                )
            )
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler { vm.stop() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus).focusable()
            .pointerInput(Unit) { detectTapGestures(onTap = { ping++ }, onLongPress = { playerMenu(vm, player, view, ch, ratio, speed, { ratio = it }, { speed = it }) }) }
            .pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(
                    onDragStart = { dy = 0f },
                    onDragEnd = { if (dy < -120f) vm.zap(1) else if (dy > 120f) vm.zap(-1) }
                ) { _, d -> dy += d }
            }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = when (ev.key) {
                    Key.Zero, Key.NumPad0 -> '0'; Key.One, Key.NumPad1 -> '1'; Key.Two, Key.NumPad2 -> '2'
                    Key.Three, Key.NumPad3 -> '3'; Key.Four, Key.NumPad4 -> '4'; Key.Five, Key.NumPad5 -> '5'
                    Key.Six, Key.NumPad6 -> '6'; Key.Seven, Key.NumPad7 -> '7'; Key.Eight, Key.NumPad8 -> '8'
                    Key.Nine, Key.NumPad9 -> '9'; else -> null
                }
                if (digit != null && live) { if (digits.length < 4) digits += digit; ping++; return@onPreviewKeyEvent true }
                when (ev.key) {
                    Key.DirectionUp, Key.ChannelUp -> if (vm.zapEnabled) { vm.zap(1); true } else false
                    Key.DirectionDown, Key.ChannelDown -> if (vm.zapEnabled) { vm.zap(-1); true } else false
                    Key.DirectionCenter, Key.Enter -> {
                        if (!live) player.playWhenReady = !player.playWhenReady
                        ping++; true
                    }
                    Key.DirectionRight -> {
                        if (live) vm.toggleFav(ch) else player.seekTo(player.currentPosition + 10_000)
                        ping++; true
                    }
                    Key.DirectionLeft -> if (!live) { player.seekTo(maxOf(0L, player.currentPosition - 10_000)); true } else false
                    Key.Menu, Key.Info, Key.M -> { playerMenu(vm, player, view, ch, ratio, speed, { ratio = it }, { speed = it }); true }
                    Key.MediaNext -> { if (vod) vm.playNext(); true }
                    else -> false
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerView(c).apply {
                    useController = !live
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                    view = this
                }
            },
            update = { it.resizeMode = ratios[ratio].second }
        )

        if (digits.isNotEmpty()) Text(digits, fontSize = 44.sp, color = Accent, modifier = Modifier.align(Alignment.TopEnd).padding(32.dp))

        nextEp?.let { e ->
            Column(Modifier.align(Alignment.BottomEnd).padding(32.dp).background(Color(0xDD000000)).padding(16.dp)) {
                Text("الحلقة التالية خلال 5 ثوانٍ: حلقة ${e.num}", color = Color.White, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MBtn("تشغيل الآن") { vm.playNext() }
                    MBtn("إلغاء", filled = false) { nextEp = null }
                }
            }
        }

        if ((overlay && live) || status.isNotEmpty()) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000)).padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(model = ch.logo, contentDescription = null, modifier = Modifier.size(56.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text((if (ch.url in vm.favs) "★ " else "") + ch.name, fontSize = 24.sp, color = Color.White)
                    val (now, next) = vm.nowNext(ch)
                    Text(now?.title ?: ch.group, fontSize = 14.sp, color = Accent)
                    if (now != null) {
                        val pct = ((System.currentTimeMillis() - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                        Box(Modifier.padding(vertical = 6.dp).width(320.dp).height(4.dp).background(Color(0x33FFFFFF))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(pct).background(Accent))
                        }
                        Text("${hm(now.start)} - ${hm(now.stop)}", fontSize = 12.sp, color = Color(0xFFB8C2CF))
                        next?.let { Text("التالي: ${it.title} (${hm(it.start)})", fontSize = 12.sp, color = Color(0xFFB8C2CF)) }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (status.isNotEmpty()) Text(status, color = Color.White)
            }
        }
    }
}

/** Player options: audio track, subtitles, aspect ratio, speed, alternative sources. */
@OptIn(UnstableApi::class)
private fun playerMenu(
    vm: AppViewModel, player: ExoPlayer, view: PlayerView?, ch: Channel,
    ratio: Int, speed: Float, setRatio: (Int) -> Unit, setSpeed: (Float) -> Unit
) {
    val items = ArrayList<Pair<String, () -> Unit>>()
    fun tracks(type: Int) = player.currentTracks.groups.filter { it.type == type && it.length > 0 }

    items += "الصوت" to {
        val g = tracks(C.TRACK_TYPE_AUDIO)
        if (g.isEmpty()) vm.say("لا توجد مسارات صوت متعددة") else { vm.menu = MenuRequest("الصوت",
            g.flatMapIndexed { _, grp ->
                (0 until grp.length).map { i ->
                    val f = grp.getTrackFormat(i)
                    val name = listOfNotNull(f.label, f.language, f.channelCount.takeIf { it > 0 }?.let { "${it}ch" }).joinToString(" · ").ifEmpty { "مسار ${i + 1}" }
                    (if (grp.isTrackSelected(i)) "● " else "") + name to {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(grp.mediaTrackGroup, i)).build()
                    }
                }
            }) }
    }
    items += "الترجمة" to {
        val g = tracks(C.TRACK_TYPE_TEXT)
        if (g.isEmpty()) vm.say("لا توجد ترجمة لهذا المحتوى") else { vm.menu = MenuRequest("الترجمة",
            listOf("إيقاف الترجمة" to {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }) + g.flatMap { grp ->
                (0 until grp.length).map { i ->
                    val f = grp.getTrackFormat(i)
                    val name = listOfNotNull(f.label, f.language).joinToString(" · ").ifEmpty { "ترجمة ${i + 1}" }
                    (if (grp.isTrackSelected(i)) "● " else "") + name to {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(grp.mediaTrackGroup, i)).build()
                    }
                }
            }) }
    }
    items += "نسبة العرض: ${ratios[ratio].first}" to { setRatio((ratio + 1) % ratios.size) }
    if (vm.playMode != PlayMode.Live) items += "السرعة: ${speed}x" to {
        val steps = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        val n = steps[(steps.indexOf(speed).let { if (it < 0) 1 else it } + 1) % steps.size]
        player.setPlaybackSpeed(n); setSpeed(n)
    }
    items += "حجم الترجمة +" to { vm.store.subSize = (vm.store.subSize + 0.01f).coerceAtMost(0.12f); view?.subtitleView?.setFractionalTextSize(vm.store.subSize) }
    items += "حجم الترجمة −" to { vm.store.subSize = (vm.store.subSize - 0.01f).coerceAtLeast(0.03f); view?.subtitleView?.setFractionalTextSize(vm.store.subSize) }
    if (vm.playMode == PlayMode.Live) {
        val alts = vm.alternativesOf(ch)
        if (alts.isNotEmpty()) items += "مصادر بديلة (${alts.size})" to {
            vm.menu = MenuRequest("مصادر بديلة", alts.map { a -> (a.name + " · " + vm.health.state(a.url).name) to { vm.useAlternative(a) } })
        }
    }
    vm.menu = MenuRequest(ch.name, items)
}

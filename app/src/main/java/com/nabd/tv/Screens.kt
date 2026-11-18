package com.nabd.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import android.widget.Toast
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/* ───────────── Setup ───────────── */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SetupScreen(vm: AppViewModel) {
    var url by remember { mutableStateOf(vm.savedUrl) }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val firstFocus = remember { FocusRequester() }
    val ctx = LocalContext.current
    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
    LaunchedEffect(vm.error) { vm.error?.let { toast(it) } }
    LaunchedEffect(vm.loading) { if (vm.loading) toast("جارٍ الاتصال بالسيرفر...") }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    BackHandler(enabled = vm.all.isNotEmpty()) { vm.closeSetup() }

    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Nabd TV", fontSize = 40.sp, color = Accent)
        if (vm.loading) Text("جارٍ الاتصال وتحميل القنوات...", color = Accent)
        vm.error?.let { Text("خطأ: $it", color = Color(0xFFFF6B6B)) }
        Spacer(Modifier.height(20.dp))

        Text("Xtream Codes", fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = host, onValueChange = { host = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(0.7f),
            placeholder = { androidx.compose.material3.Text("http://server:port") }
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = user, onValueChange = { user = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(0.7f),
            placeholder = { androidx.compose.material3.Text("Username") }
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = pass, onValueChange = { pass = it }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(0.7f),
            placeholder = { androidx.compose.material3.Text("Password") }
        )
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.Button(
            onClick = { if (host.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) vm.setXtream(host, user, pass) else toast("اكتب السيرفر واليوزر والباسورد") },
            modifier = Modifier.focusRequester(firstFocus)
        ) { androidx.compose.material3.Text("دخول Xtream") }

        Spacer(Modifier.height(20.dp))
        Text("أو رابط M3U", fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = url, onValueChange = { url = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(0.7f),
            placeholder = { androidx.compose.material3.Text("http://.../playlist.m3u") }
        )
        Spacer(Modifier.height(10.dp))
        MBtn("تحميل M3U") { if (url.isNotBlank()) vm.setUrl(url) else toast("اكتب رابط M3U") }

    }
}

/* ───────────── Home ───────────── */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel) {
    Row(Modifier.fillMaxSize().background(Bg).padding(horizontal = 32.dp, vertical = 24.dp)) {
        Column(Modifier.width(230.dp)) {
            Text("Nabd TV", fontSize = 28.sp, color = Accent)
            Spacer(Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                items(vm.groups, key = { it }) { g ->
                    Focusable(selected = vm.group == g, onClick = { vm.group = g }, modifier = Modifier.fillMaxWidth()) {
                        Text(g, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            MBtn(if (vm.loading) "..." else "تحديث", filled = false) { vm.load(true) }
            Spacer(Modifier.height(6.dp))
            MBtn("القائمة", filled = false) { vm.openSetup() }
        }
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            OutlinedTextField(
                value = vm.query, onValueChange = { vm.query = it },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { androidx.compose.material3.Text("بحث...") }
            )
            Spacer(Modifier.height(12.dp))
            Text("${vm.visible.size} قناة", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(170.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(6.dp)
            ) {
                items(vm.visible, key = { it.id }) { ch ->
                    Focusable(onClick = { vm.play(ch) }, onLongClick = { vm.toggleFav(ch) }, modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            AsyncImage(model = ch.logo, contentDescription = null, modifier = Modifier.size(72.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                (if (ch.url in vm.favs) "★ " else "") + ch.name,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center, fontSize = 14.sp
                            )
                            vm.nowNext(ch).first?.let {
                                Text(it.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = Accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ───────────── Player ───────────── */
@OptIn(UnstableApi::class)
fun buildPlayer(ctx: android.content.Context): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent(httpUa)
        .setConnectTimeoutMs(10_000).setReadTimeoutMs(10_000)
    // Small buffers = fast channel start / fast zapping
    val load = DefaultLoadControl.Builder().setBufferDurationsMs(3000, 20000, 1000, 2000).build()
    return ExoPlayer.Builder(ctx)
        .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(http))
        .setLoadControl(load).build()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val ch = vm.current ?: return
    val player = remember { buildPlayer(ctx) }
    DisposableEffect(Unit) { onDispose { player.release() } }

    var retries by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var ping by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf(true) }

    LaunchedEffect(ch.url) {
        retries = 0; status = ""
        player.setMediaItem(MediaItem.fromUri(ch.url))
        player.prepare(); player.playWhenReady = true
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare()
                } else if (retries < 3) { retries++; player.prepare() }
                else status = "تعذّر تشغيل القناة"
            }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> status = "جارٍ التحميل..."
                    Player.STATE_READY -> { status = ""; retries = 0 }
                }
            }
        }
        player.addListener(l); onDispose { player.removeListener(l) }
    }
    LaunchedEffect(ch.url, ping) { overlay = true; delay(3500); overlay = false }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler { vm.stop() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus).focusable()
            .pointerInput(Unit) { detectTapGestures(onTap = { ping++ }) }
            .pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(
                    onDragStart = { dy = 0f },
                    onDragEnd = { if (dy < -120f) vm.zap(1) else if (dy > 120f) vm.zap(-1) }
                ) { _, d -> dy += d }
            }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionUp, Key.ChannelUp -> { vm.zap(1); true }
                    Key.DirectionDown, Key.ChannelDown -> { vm.zap(-1); true }
                    Key.DirectionCenter, Key.Enter -> { ping++; true }
                    Key.DirectionRight -> { vm.toggleFav(ch); ping++; true }
                    else -> false
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                }
            }
        )
        if (overlay || status.isNotEmpty()) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0xCC000000)).padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(model = ch.logo, contentDescription = null, modifier = Modifier.size(56.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text((if (ch.url in vm.favs) "★ " else "") + ch.name, fontSize = 24.sp, color = Color.White)
                    val (now, next) = vm.nowNext(ch)
                    Text(now?.title ?: ch.group, fontSize = 14.sp, color = Accent)
                    if (now != null) {
                        val pct = ((System.currentTimeMillis() - now.start).toFloat() / (now.stop - now.start)).coerceIn(0f, 1f)
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

fun hm(t: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))

/** Button that works with both touch and D-pad. */
@Composable
fun MBtn(text: String, filled: Boolean = true, onClick: () -> Unit) {
    if (filled) androidx.compose.material3.Button(onClick = onClick) { androidx.compose.material3.Text(text) }
    else androidx.compose.material3.OutlinedButton(onClick = onClick) { androidx.compose.material3.Text(text) }
}

/** Focusable tile: touch tap/long-press + D-pad OK/long-OK, with a visible focus ring. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Focusable(
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) Color(0xFF263241) else if (selected) Color(0xFF1B2430) else Surf)
            .border(2.dp, if (focused) Accent else Color.Transparent, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        content = content
    )
}

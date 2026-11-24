package com.nabd.tv

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MainScreen(vm: AppViewModel) {
    val d = LocalDevice.current
    BackHandler(enabled = vm.tab != Tab.Home || vm.query.isNotBlank()) {
        if (vm.query.isNotBlank()) vm.query = "" else vm.selectTab(Tab.Home)
    }
    LaunchedEffect(vm.query, vm.rev) { vm.runSearch() }
    LaunchedEffect(Unit) {
        if (d.tv) {
            repeat(25) { delay(120); if (vm.enterContent()) { vm.restoreFocus = false; return@LaunchedEffect } }
        }
        vm.restoreFocus = false
    }
    LaunchedEffect(vm.focusContentTick) {
        if (vm.focusContentTick == 0 || !d.tv) return@LaunchedEffect
        delay(320)
        repeat(15) { if (vm.enterContent()) return@LaunchedEffect; delay(130) }
    }

    // The header floats ON TOP of the page. On Home the hero artwork runs behind it (edge to edge);
    // on the other pages the content simply starts below the measured header height.
    val onHome = vm.tab == Tab.Home && vm.query.isBlank()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var barH by remember { mutableStateOf(0.dp) }
    Box(Modifier.fillMaxSize()) {
        Content(vm, d, barH, Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .onSizeChanged { barH = with(density) { it.height.toDp() } }
        ) {
            AppBar(vm, d, onArt = onHome)
            StatusStrip(vm, d)
        }
    }
}

/** Update banner + loading / error / health line. Overlays the page just under the header. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatusStrip(vm: AppViewModel, d: Device) {
    val edge = edgePad(d)
    Column(Modifier.fillMaxWidth().padding(start = edge.start, end = edge.end)) {
        vm.update?.let { u ->
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(Radius.small)).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NIcon(Ic.Download, Accent, 20.dp)
                Text(tr(R.string.update_available, u.tag), color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                MBtn(vm.updateProgress?.let { tr(R.string.update_downloading, it) } ?: tr(R.string.update_now)) { vm.installUpdate() }
            }
        }
        if (vm.loading || vm.vodLoading || vm.error != null || vm.healthStatus != null) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (vm.loading || vm.vodLoading) Text(tr(R.string.common_loading), color = Accent, fontSize = 12.sp)
                vm.error?.let { Text(tr(R.string.detail_error, it), color = Danger, fontSize = 12.sp) }
                vm.healthStatus?.let { Text(tr(R.string.health_status, it), color = Accent, fontSize = 12.sp) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Content(vm: AppViewModel, d: Device, barH: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val searching = vm.query.isNotBlank() && (vm.tab == Tab.Home || vm.tab == Tab.Guide || vm.tab == Tab.Recordings)
    val edge = edgePad(d)
    val key = if (searching) "search" else vm.tab.name
    AnimatedContent(
        targetState = key,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(220)) + scaleIn(initialScale = 0.99f, animationSpec = tween(220))) togetherWith fadeOut(tween(110))
        },
        label = "page"
    ) { k ->
        Box(Modifier.fillMaxSize()) {
            if (k == Tab.Home.name) {
                HomeTab(vm, d)   // full-bleed: manages its own side padding
            } else {
                Box(Modifier.fillMaxSize().padding(start = edge.start, end = edge.end, top = barH + 6.dp)) {
                    when (k) {
                        "search" -> SearchResults(vm, d)
                        Tab.Guide.name -> GuideTab(vm)
                        Tab.Recordings.name -> RecordingsTab(vm)
                        else -> BrowseTab(vm, d)
                    }
                }
            }
        }
    }
}

/* ───────────────────────────── Home ───────────────────────────── */

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeTab(vm: AppViewModel, d: Device) {
    val shelves = vm.shelves
    if (shelves.isEmpty() && vm.loading) { SkeletonGrid(d); return }
    if (shelves.isEmpty()) {
        Text(tr(R.string.home_empty), color = TextSecondary, modifier = Modifier.padding(16.dp))
        return
    }

    SideEffect { vm.firstKey = shelves.firstOrNull()?.items?.firstOrNull()?.let { shelves.first().title + "|" + it.url + it.id } }
    val focus = vm.homeFocus ?: shelves.first().items.first()
    LaunchedEffect(focus.url) { delay(280); vm.loadHomeInfo(focus) }

    // Poster auto-rotation. Touch: rotates whenever nobody has touched the screen for a while.
    // TV: only while the poster still shows what the rotation chose, so it never disagrees with the card the remote is on.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val featured = remember(shelves) {
        shelves.flatMap { it.items.take(3) }.filter { kindOf(it) != Kind.Live }.distinctBy { it.url }.take(8)
    }
    val lastInput = remember { longArrayOf(System.currentTimeMillis()) }
    val lastRotate = remember { longArrayOf(System.currentTimeMillis()) }
    val autoUrl = remember { arrayOfNulls<String>(1) }      // the change we are about to see in `focus` came from the rotation
    val lastAuto = remember { arrayOfNulls<String>(1) }     // the title the rotation put on screen last
    LaunchedEffect(focus.url) { if (autoUrl[0] == focus.url) autoUrl[0] = null else lastInput[0] = System.currentTimeMillis() }
    val reduceMotion = remember {
        runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    LaunchedEffect(vm.heroRotate, featured, d.tv, reduceMotion) {
        if (!vm.heroRotate || reduceMotion || featured.size < 2) return@LaunchedEffect
        while (true) {
            delay(1000)
            val now = System.currentTimeMillis()
            if (!AppState.foreground) continue
            val held = vm.homeFocus
            if (d.tv && held != null && held.url != lastAuto[0]) continue
            if (!HeroRotation.due(now, lastInput[0], lastRotate[0])) continue
            val cur = held ?: focus
            val nxt = HeroRotation.next(featured, featured.firstOrNull { it.url == cur.url }) ?: continue
            lastRotate[0] = now
            autoUrl[0] = nxt.url; lastAuto[0] = nxt.url
            vm.homeFocus = nxt
            // Warm up the picture of the title after it, so the next switch does not show an empty frame.
            HeroRotation.next(featured, nxt)?.let { up -> launch { ArtStore.resolve(vm, up) } }
        }
    }

    val heroArt = vm.homeInfo?.backdrop ?: focus.logo
    val palette = rememberArtworkPalette(heroArt)

    // Touch devices: swipe the big hero image sideways to jump to the next / previous title of its row.
    val rtl = LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val swipe = if (d.tv) Modifier else Modifier.pointerInput(focus.url, shelves.size, rtl) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onHorizontalDrag = { _, dx -> total += dx },
            onDragEnd = {
                if (kotlin.math.abs(total) > 60.dp.toPx()) {
                    // Finger moves left = next title (mirrored in RTL).
                    heroStep(shelves, focus, next = (total < 0f) != rtl)?.let { vm.homeFocus = it }
                }
            }
        )
    }.then(
        // Tapping (or pressing OK on) the big image opens that movie / series page (live channels start playing).
        // On TV the same clickable makes the hero the focus target: OK on the remote opens the title.
        Modifier.clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null
        ) { if (kindOf(focus) == Kind.Live) vm.play(focus) else vm.openDetail(focus) }
    )
    val edge = edgePad(d)
    val contItems = vm.continueItems
    Column(Modifier.fillMaxSize()) {
        // Edge to edge, starts at the very top of the screen: the floating header sits on this artwork.
        HomeHero(vm, focus, vm.homeInfo, d, palette, Modifier.fillMaxWidth().height(if (d.tv) d.oneRowHeroHeight else d.heroHeight).then(swipe))
        Spacer(Modifier.height(4.dp))
        // TV: the poster stays put and only ONE row is shown under it; Up / Down swap the row (see OneRowHome).
        if (d.tv) OneRowHome(vm, d, shelves, contItems, Modifier.weight(1f).fillMaxWidth()) else {
        // Rows that scroll up under the hero dissolve softly instead of being cut by a hard line; at the very top nothing is faded.
        val homeState = vm.listState("home")
        val topFade = remember(homeState) {
            derivedStateOf {
                if (homeState.firstVisibleItemIndex > 0) 1f else (homeState.firstVisibleItemScrollOffset / (if (d.tv) 72f else 48f)).coerceIn(0f, 1f)
            }
        }
        LazyColumn(
            state = homeState,
            modifier = Modifier.weight(1f).fadeTopEdge(if (d.tv) 72.dp else 40.dp) { topFade.value },   // taller soft edge on TV
            verticalArrangement = Arrangement.spacedBy(if (d.tv) 34.dp else 24.dp),   // one gap between every row on Home (continue / trending / new / favourites ...)
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            if (contItems.isNotEmpty()) item(key = "continue-row") { ContinueRow(vm, d, contItems, edge.start, edge.end) }
            items(shelves, key = { it.title }) { s ->
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = edge.start, end = edge.end, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            s.title,
                            fontSize = if (d.tv) 17.sp else 15.sp, fontWeight = FontWeight.Bold, color = Ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        Text("${s.items.size}", fontSize = 12.sp, color = TextMuted)
                    }
                    LazyRow(
                        state = vm.listState("row:" + s.title),
                        horizontalArrangement = Arrangement.spacedBy(d.gap),
                        contentPadding = PaddingValues(start = edge.start, end = edge.end, top = 4.dp, bottom = 4.dp)
                    ) {
                        itemsIndexed(s.items) { i, c -> HomeCard(vm, d, s, c, i + 1) }
                    }
                }
            }
        }
        }
    }
}

/**
 * TV home, one row at a time. The poster stays where it is (taller, ~58% of the screen) and under it only ONE row is
 * visible - nothing above or below it. Down / Up on the remote fade the row out and the next / previous one in
 * (continue watching, trending movies, trending series, ...). Up on the first row falls through to the poster and the
 * header as before; Down on the last row does nothing. The row you were on (and the card in it) is remembered when you
 * open a title and come back. Dots on the right show where you are.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OneRowHome(vm: AppViewModel, d: Device, shelves: List<Shelf>, cont: List<ContinueItem>, modifier: Modifier) {
    val edge = edgePad(d)
    val hasCont = cont.isNotEmpty()
    val count = shelves.size + if (hasCont) 1 else 0
    if (count == 0) return
    val idx = vm.homeRow.coerceIn(0, count - 1)
    fun shelfAt(i: Int): Shelf? = shelves.getOrNull(if (hasCont) i - 1 else i)
    fun firstKeyOf(i: Int): String? = shelfAt(i)?.let { sh -> sh.items.firstOrNull()?.let { sh.title + "|" + it.url + it.id } }

    val rowFocus = remember { FocusRequester() }
    val pendingFocus = remember { BooleanArray(1) }
    val lastKey = remember { HashMap<Int, String>() }
    var shown by remember { mutableIntStateOf(idx) }       // the row on screen (changes only while it is faded out)
    val fade = remember { Animatable(1f) }

    // Entry point for "go to the content" from the header / after returning from a title.
    SideEffect { vm.firstKey = firstKeyOf(shown.coerceIn(0, count - 1)) ?: "home-row" }
    DisposableEffect(Unit) {
        vm.cardFocus["home-row"] = rowFocus
        onDispose { if (vm.cardFocus["home-row"] === rowFocus) vm.cardFocus.remove("home-row") }
    }

    LaunchedEffect(idx) {
        if (shown != idx) {
            fade.animateTo(0f, tween(110))
            shown = idx
        }
        if (pendingFocus[0]) {
            pendingFocus[0] = false
            // The new row is composed after the swap: take focus as soon as one of its cards exists.
            for (attempt in 0 until 12) {
                delay(35)
                val fr = lastKey[idx]?.let { vm.cardFocus[it] } ?: firstKeyOf(idx)?.let { vm.cardFocus[it] } ?: rowFocus
                if (runCatching { fr.requestFocus(); true }.getOrDefault(false)) break
            }
        }
        fade.animateTo(1f, tween(140))
    }

    BoxWithConstraints(
        modifier.focusRequester(rowFocus).focusGroup().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val held = e.nativeKeyEvent.repeatCount > 0     // holding the key does not race through all the rows
            when (e.key) {
                Key.DirectionDown -> { if (!held && idx < count - 1) { pendingFocus[0] = true; vm.homeRow = idx + 1 }; true }
                Key.DirectionUp -> if (idx > 0) { if (!held) { pendingFocus[0] = true; vm.homeRow = idx - 1 }; true } else false
                else -> false
            }
        }
    ) {
        // Card width from the height that is really left under the poster (title + caption + padding ~ 84dp).
        val cardW = ((maxHeight - 84.dp) * (16f / 9f)).coerceIn(120.dp, d.shelfPoster * 2.6f)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
            val s = shown.coerceIn(0, count - 1)
            val shelf = shelfAt(s)
            if (shelf == null) {
                ContinueRow(vm, d, cont, edge.start, edge.end, Modifier.padding(top = 8.dp))
            } else {
                Column(Modifier.padding(top = 8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = edge.start, end = edge.end, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            shelf.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        Text("${shelf.items.size}", fontSize = 12.sp, color = TextMuted)
                    }
                    LazyRow(
                        state = vm.listState("row:" + shelf.title),
                        horizontalArrangement = Arrangement.spacedBy(d.gap),
                        contentPadding = PaddingValues(start = edge.start, end = edge.end, top = 4.dp, bottom = 4.dp)
                    ) {
                        itemsIndexed(shelf.items) { i, c -> HomeCard(vm, d, shelf, c, i + 1, cardW) { k -> lastKey[s] = k } }
                    }
                }
            }
        }
        // Position dots: always on the physical right edge.
        androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(count) { i ->
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (i == idx) Color.White else Color(0x664B5280)))
                }
            }
        }
    }
}

/** Next / previous playable title in the row the hero currently comes from (wraps around); null when there is nothing to switch to. */
private fun heroStep(shelves: List<Shelf>, cur: Channel, next: Boolean): Channel? {
    val shelf = shelves.firstOrNull { sh -> sh.items.any { it.url == cur.url } } ?: shelves.firstOrNull() ?: return null
    val items = shelf.items.filter { kindOf(it) != Kind.Live }
    if (items.size < 2) return null
    val i = items.indexOfFirst { it.url == cur.url }
    val n = if (i < 0) 0 else if (next) (i + 1) % items.size else (i - 1 + items.size) % items.size
    return items[n]
}

/**
 * Full-bleed cinematic hero: a wide backdrop runs edge to edge (and behind the floating header), fades into the page,
 * and the title, metadata, synopsis and actions sit on the start side at the bottom (mirrors automatically in RTL).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeHero(
    vm: AppViewModel,
    c: Channel,
    info: VodInfo?,
    d: Device,
    palette: ArtworkPalette,
    modifier: Modifier = Modifier
) {
    val kind = kindOf(c)
    val art = info?.backdrop ?: c.logo

    val edge = edgePad(d)
    val wide = d.widthDp >= 700
    val shortScreen = d.heightDp < 420
    // Feed the details that arrived for THIS title into the shared poster/backdrop store.
    LaunchedEffect(c.url, info?.backdrop, info?.poster) { if (vm.homeInfoUrl == c.url) ArtStore.put(c, info?.poster, info?.backdrop) }

    Box(modifier) {
        Box(Modifier.fillMaxSize().fadeOutBottom(0.55f)) {
        // 1. Artwork: wide backdrop, cropped (never stretched), subject kept in view.
        if (kind == Kind.Live) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(SurfHigh, NavyDeep))))
            AsyncImage(model = c.logo, contentDescription = c.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(if (d.tv) 40.dp else 24.dp).alpha(0.25f))
        } else {
            // Portrait screen = the portrait poster, landscape screen = the wide backdrop (same title id; either falls back to the other).
            AdaptiveArtImage(vm, c, landscape = !d.portrait, modifier = Modifier.fillMaxSize())
        }
        // 2. Layered gradients: text side wash, top wash for the floating header, bottom fade into the page.
        Box(Modifier.fillMaxSize().textSideScrim(if (wide) 0.95f else 0.78f))
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.42f).background(Brush.verticalGradient(listOf(Scrim.copy(alpha = 0.72f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.0f to Color.Transparent, 0.38f to Scrim.copy(alpha = 0.18f), 0.74f to Scrim.copy(alpha = 0.70f), 1f to Scrim.copy(alpha = 0.90f))))

        }
        // 3. Info block: anchored to the start side, bottom of the artwork; the right/centre of the image stays clear.
        Column(
            Modifier.align(Alignment.BottomStart)
                .then(if (wide) Modifier.fillMaxWidth(0.55f) else Modifier.fillMaxWidth())
                .padding(start = edge.start, end = edge.end, bottom = if (d.tv) 22.dp else 12.dp)
        ) {
            TitleLogo(c.name, kindOf(c), null, height = if (d.tv) 58.dp else if (d.widthDp >= 600) 50.dp else 40.dp) {
                Text(c.name, style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.65f), Offset(0f, 3f), 14f)), fontSize = if (d.tv) 26.sp else if (d.widthDp >= 600) 22.sp else 18.sp, lineHeight = if (d.tv) 32.sp else if (d.widthDp >= 600) 28.sp else 23.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeCard(vm: AppViewModel, d: Device, s: Shelf, ch: Channel, rank: Int, wideWidth: Dp? = null, onFocused: (String) -> Unit = {}) {
    val key = s.title + "|" + ch.url + ch.id
    Row(verticalAlignment = Alignment.Bottom) {
        val art = s.art[ch.url]
        PosterCard(
            vm, ch,
            modifier = Modifier.width(if (!d.portrait && kindOf(ch) != Kind.Live) (wideWidth ?: (d.shelfPoster * 2.6f)) else d.shelfPoster),
            focusKey = key,
            onFocusExtra = { vm.homeFocus = ch; onFocused(key) },
            tmdbArt = art,
            adaptive = true,
            rank = if (s.ranked) rank else null
        )
    }
}

/** Shimmering placeholders while the first list loads. */
@Composable
private fun SkeletonGrid(d: Device) {
    val ph = rememberShimmerPhase()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(d.posterMin), userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(d.gap), verticalArrangement = Arrangement.spacedBy(d.gap + 10.dp)
    ) {
        items(18) {
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(Radius.poster)).background(SurfHigh).shimmer(ph))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(0.7f).height(9.dp).clip(RoundedCornerShape(4.dp)).background(SurfHigh).shimmer(ph))
            }
        }
    }
}

/* ─────────────── Live / Movies / Series browsing ─────────────── */

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GroupItem(vm: AppViewModel, g: String, modifier: Modifier = Modifier) {
    val sel = vm.group == g
    Focusable(
        selected = sel, onClick = { vm.group = g }, onLongClick = { vm.groupMenu(g) },
        modifier = modifier, zoom = 1.03f, shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            vm.groupLabel(g), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
            color = if (sel) Accent else if (Ui.dark) Ink.copy(alpha = 0.82f) else Ink,
            modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = LocalDevice.current.control).wrapContentHeight(Alignment.CenterVertically)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BrowseTab(vm: AppViewModel, d: Device) {
    val poster = vm.tab != Tab.Live
    val items = vm.visible

    SideEffect { vm.firstKey = items.firstOrNull()?.let { it.url + it.id } }

    // Continue watching has its own category (GROUP_CONT): movies in Movies, episodes in Series.
    val cont = when (vm.tab) {
        Tab.Movies -> vm.continueItems.filter { it.kind == Kind.Movie }
        Tab.Series -> vm.continueItems.filter { it.kind == Kind.Series }
        else -> emptyList()
    }
    val showCont = vm.group == GROUP_CONT && vm.tab != Tab.Live

    @Composable
    fun Grid(modifier: Modifier) {
        if (showCont) {
            if (cont.isEmpty()) Text(tr(R.string.no_results), color = TextSecondary, fontSize = 15.sp)
            LazyVerticalGrid(
                state = vm.gridState(vm.tab.name + "|" + GROUP_CONT),
                columns = GridCells.Adaptive(if (d.tv) 150.dp else 118.dp),
                horizontalArrangement = Arrangement.spacedBy(d.gap), verticalArrangement = Arrangement.spacedBy(d.gap + 6.dp),
                contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 24.dp), modifier = modifier
            ) {
                items(cont, key = { it.key }) { ContinueCard(vm, d, it) }
            }
            return
        }
        if (items.isEmpty() && (vm.loading || vm.vodLoading)) { SkeletonGrid(d); return }
        if (items.isEmpty()) Text(tr(R.string.no_results), color = TextSecondary, fontSize = 15.sp)
        LazyVerticalGrid(
            state = vm.gridState(vm.tab.name + "|" + vm.group),
            columns = GridCells.Adaptive(if (poster) d.posterMin else d.liveMin),
            horizontalArrangement = Arrangement.spacedBy(d.gap), verticalArrangement = Arrangement.spacedBy(d.gap + 6.dp),
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 24.dp), modifier = modifier
        ) {
            items(items, key = { it.url + it.id }) { ch -> if (poster) PosterCard(vm, ch) else LiveCard(vm, ch) }
        }
    }

    if (d.twoPane) {
        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.width(d.sideList), state = vm.listState("groups:" + vm.tab.name),
                verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(2.dp)
            ) {
                items(vm.groups, key = { it }) { g -> GroupItem(vm, g, Modifier.fillMaxWidth()) }
                item {
                    if (vm.tab == Tab.Live) {
                        Spacer(Modifier.height(10.dp))
                        MBtn(tr(R.string.multi_view_btn, vm.multi.size), filled = false, modifier = Modifier.fillMaxWidth()) { vm.openMulti() }
                    }
                }
            }
            Spacer(Modifier.width(d.gap + 6.dp))
            Column(Modifier.weight(1f)) {
                Text(tr(R.string.items_count, if (showCont) cont.size else items.size), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                Grid(Modifier.weight(1f))
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            LazyRow(
                state = vm.listState("chips:" + vm.tab.name),
                horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
            ) {
                items(vm.groups, key = { it }) { g -> GroupItem(vm, g) }
                if (vm.tab == Tab.Live) item { TabChip(tr(R.string.multi_chip, vm.multi.size), false) { vm.openMulti() } }
            }
            Spacer(Modifier.height(4.dp))
            Grid(Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchResults(vm: AppViewModel, d: Device) {
    val r = vm.searchResults
    val progs = vm.progResults
    SideEffect { vm.firstKey = if (progs.isNotEmpty()) null else r.firstOrNull()?.let { it.url + it.id } }
    Column {
        Text(tr(R.string.search_results, r.size + progs.size), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        LazyVerticalGrid(
            state = vm.gridState("search"),
            columns = GridCells.Adaptive(if (r.any { kindOf(it) == Kind.Live }) d.liveMin else d.posterMin),
            horizontalArrangement = Arrangement.spacedBy(d.gap), verticalArrangement = Arrangement.spacedBy(d.gap),
            contentPadding = PaddingValues(2.dp)
        ) {
            if (progs.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "prog-head") {
                    Text(tr(R.string.search_epg, progs.size), color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                items(progs, key = { "p" + it.first.url + it.second.start }, span = { GridItemSpan(maxLineSpan) }) { (c, p) ->
                    ProgRow(vm, c, p)
                }
                if (r.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "prog-tail") {
                    Text(tr(R.string.search_all, r.size), color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            items(r, key = { it.url + it.id }) { c -> if (kindOf(c) == Kind.Live) LiveCard(vm, c) else PosterCard(vm, c) }
        }
    }
}

/** A program found in the EPG: tap to watch the channel (airing now) or choose watch / record (upcoming). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProgRow(vm: AppViewModel, c: Channel, p: Prog) {
    val airing = p.start <= System.currentTimeMillis()
    val watchLbl = tr(R.string.prog_watch)
    val recLbl = tr(R.string.prog_record)
    Focusable(
        onClick = {
            if (airing) vm.play(c)
            else vm.menu = MenuRequest(p.title, listOf(watchLbl to { vm.play(c) }, recLbl to { vm.record(c, p) }))
        },
        modifier = Modifier.fillMaxWidth(), zoom = 1.02f, shape = RoundedCornerShape(Radius.small)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (airing) tr(R.string.now) else dayHm(p.start), color = if (airing) Accent else TextSecondary,
                fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(84.dp), maxLines = 1
            )
            Column(Modifier.weight(1f)) {
                Text(p.title, fontSize = 14.sp, maxLines = 1, color = Ink)
                Text(c.name, fontSize = 12.sp, maxLines = 1, color = TextSecondary)
            }
        }
    }
}

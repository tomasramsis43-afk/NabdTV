package com.nabd.tv

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
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
import androidx.compose.ui.graphics.drawscope.Stroke
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
        val blurR by transition.animateDp(label = "blur") { s -> if (s == EnterExitState.Visible) 0.dp else 8.dp }
        Box(Modifier.fillMaxSize().then(if (!d.tv && Build.VERSION.SDK_INT >= 31 && blurR > 0.dp) Modifier.blur(blurR) else Modifier)) {
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

    val heroArt = vm.homeInfo?.backdrop ?: focus.logo
    val palette = rememberArtworkPalette(heroArt)

    val edge = edgePad(d)
    Column(Modifier.fillMaxSize()) {
        // Edge to edge, starts at the very top of the screen: the floating header sits on this artwork.
        HomeHero(vm, focus, vm.homeInfo, d, palette, Modifier.fillMaxWidth().height(d.heroHeight))
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            state = vm.listState("home"),
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (d.tv) 14.dp else 12.dp),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
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
                        horizontalArrangement = Arrangement.spacedBy(if (s.ranked) d.gap + 16.dp else d.gap),
                        contentPadding = PaddingValues(start = edge.start, end = edge.end, top = 4.dp, bottom = 4.dp)
                    ) {
                        itemsIndexed(s.items) { i, c -> HomeCard(vm, d, s, c, i + 1) }
                    }
                }
            }
        }
    }
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
    val typeLabel = when (kind) { Kind.Movie -> tr(R.string.detail_type_movie); Kind.Series -> tr(R.string.detail_type_series); Kind.Live -> tr(R.string.type_live) }
    val primaryLabel = when (kind) { Kind.Series -> tr(R.string.detail_episodes); Kind.Movie -> tr(R.string.play); Kind.Live -> tr(R.string.watch) }
    val art = info?.backdrop ?: c.logo

    val edge = edgePad(d)
    val wide = d.widthDp >= 700
    val shortScreen = d.heightDp < 420

    Box(modifier) {
        // 1. Artwork: wide backdrop, cropped (never stretched), subject kept in view.
        if (kind == Kind.Live) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(SurfHigh, NavyDeep))))
            AsyncImage(model = c.logo, contentDescription = c.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(if (d.tv) 40.dp else 24.dp).alpha(0.25f))
        } else {
            Crossfade(art, animationSpec = tween(520), label = "heroArt") { img ->
                AsyncImage(
                    model = img, contentDescription = null, contentScale = ContentScale.Crop,
                    alignment = androidx.compose.ui.BiasAlignment(0f, -0.45f), modifier = Modifier.fillMaxSize()
                )
            }
        }
        // 2. Layered gradients: text side wash, top wash for the floating header, bottom fade into the page.
        Box(Modifier.fillMaxSize().textSideScrim(if (wide) 0.80f else 0.55f))
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.42f).background(Brush.verticalGradient(listOf(Scrim.copy(alpha = 0.72f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.0f to Color.Transparent, 0.38f to Scrim.copy(alpha = 0.18f), 0.74f to Scrim.copy(alpha = 0.78f), 1f to Bg)))
        Box(Modifier.fillMaxSize().drawBehind {
            drawCircle(Brush.radialGradient(listOf(palette.glow, Color.Transparent), center = Offset(size.width * 0.5f, 0f), radius = size.width * 0.6f), radius = size.width * 0.6f, center = Offset(size.width * 0.5f, 0f))
        })

        // 3. Info block: anchored to the start side, bottom of the artwork; the right/centre of the image stays clear.
        Column(
            Modifier.align(Alignment.BottomStart)
                .then(if (wide) Modifier.fillMaxWidth(0.55f) else Modifier.fillMaxWidth())
                .padding(start = edge.start, end = edge.end, bottom = if (d.tv) 22.dp else 12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(typeLabel, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = readableOnArt(palette.accent), modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(palette.primary.copy(alpha = 0.26f)).padding(horizontal = 8.dp, vertical = 3.dp))
                if (c.hasCatchup) MetaChip(tr(R.string.catchup_chip), fg = Color(0xFFE7E9F2), bg = Color(0x26FFFFFF))
                if (c.url in vm.favs) Text(tr(R.string.detail_fav_on), fontSize = 12.sp, color = Accent2)
            }
            Spacer(Modifier.height(if (d.tv) 10.dp else 6.dp))
            Text(c.name, fontSize = if (d.tv) 42.sp else if (d.widthDp >= 600) 32.sp else 24.sp, lineHeight = if (d.tv) 48.sp else if (d.widthDp >= 600) 38.sp else 29.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                (info?.rating?.takeIf { it.isNotBlank() } ?: ratingBadge(c))?.let { MetaChip("★ $it", fg = Color(0xFFFFD27A), bg = Color(0x33FFB74D)) }
                info?.year?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
                info?.duration?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
                info?.genre?.takeIf { it.isNotBlank() }?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(if (d.tv) 2 else 1)?.forEach { MetaChip(it) }
            }
            if (!shortScreen) info?.plot?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(if (d.tv) 8.dp else 5.dp))
                Text(it, fontSize = if (d.tv) 14.sp else 12.5.sp, lineHeight = if (d.tv) 20.sp else 17.sp, color = Color(0xFFE3E6F0), maxLines = if (d.tv) 3 else 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(if (d.tv) 14.dp else 9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(if (d.tv) 12.dp else 9.dp), verticalAlignment = Alignment.CenterVertically) {
                MBtn(primaryLabel, tint = buttonTone(palette.primary)) { if (kind == Kind.Series) vm.openDetail(c) else vm.play(c) }
                if (kind != Kind.Live) MBtn(tr(R.string.details), filled = false, tint = palette.accent) { vm.openDetail(c) }
                HeroFav(vm, c, palette, d)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroFav(vm: AppViewModel, c: Channel, palette: ArtworkPalette, d: Device) {
    var focused by remember { mutableStateOf(false) }
    val fav = c.url in vm.favs
    val sc by animateFloatAsState(if (focused) 1.08f else 1f, tween(140), label = "favScale")
    val size = if (d.tv) 52.dp else 46.dp
    val iconSize = if (d.tv) 24.dp else 20.dp
    Box(
        Modifier.size(size).scale(sc)
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(if (fav) Accent2.copy(alpha = 0.26f) else Color(0x55000000))
            .border(if (focused) 2.dp else 1.5.dp, if (focused) Color.White else if (fav) Accent2.copy(alpha = 0.85f) else Color(0xCCFFFFFF), CircleShape)
            .then(if (focused) Modifier.softShadow(CircleShape, 12.dp, if (fav) Accent2 else Color.White) else Modifier)
            .clickable { vm.toggleFav(c) },
        contentAlignment = Alignment.Center
    ) {
        NIcon(Ic.Star, if (fav) Accent2 else Color.White, iconSize)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeCard(vm: AppViewModel, d: Device, s: Shelf, ch: Channel, rank: Int) {
    val key = s.title + "|" + ch.url + ch.id
    Row(verticalAlignment = Alignment.Bottom) {
        if (s.ranked) {
            Text(
                "$rank", fontSize = if (d.tv) 46.sp else 36.sp, fontWeight = FontWeight.Black,
                maxLines = 1, softWrap = false,
                style = androidx.compose.ui.text.TextStyle(
                    color = Accent.copy(alpha = 0.85f),
                    drawStyle = Stroke(width = 2.5f, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                ),
                modifier = Modifier.padding(end = 6.dp).offset(y = 0.dp)
            )
        }
        PosterCard(
            vm, ch,
            modifier = Modifier.width(d.shelfPoster),
            focusKey = key,
            onFocusExtra = { vm.homeFocus = ch }
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

    @Composable
    fun Grid(modifier: Modifier) {
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
                Text(tr(R.string.items_count, items.size), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                Grid(Modifier.fillMaxSize())
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
            Grid(Modifier.fillMaxSize())
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

package com.nabd.tv

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun MainScreen(vm: AppViewModel) {
    val d = LocalDevice.current
    // Back from a non-home tab returns to Home instead of leaving the app.
    BackHandler(enabled = vm.tab != Tab.Home || vm.query.isNotBlank()) {
        if (vm.query.isNotBlank()) vm.query = "" else vm.selectTab(Tab.Home)
    }
    LaunchedEffect(vm.query, vm.rev) { vm.runSearch() }
    // TV: land on the card you left (after a movie / player / settings) or on the first card, retrying while the page loads.
    LaunchedEffect(Unit) {
        if (d.tv) {
            repeat(25) { delay(120); if (vm.enterContent()) { vm.restoreFocus = false; return@LaunchedEffect } }
        }
        vm.restoreFocus = false
    }
    // Pressing a section icon starts at the first card of that section.
    LaunchedEffect(vm.focusContentTick) {
        if (vm.focusContentTick == 0 || !d.tv) return@LaunchedEffect
        delay(320)
        repeat(15) { if (vm.enterContent()) return@LaunchedEffect; delay(130) }
    }

    Box(Modifier.fillMaxSize()) {
        if (d.nav == NavMode.Bottom) {
            Column(Modifier.fillMaxSize()) {
                Content(vm, d, Modifier.weight(1f))
                BottomBar(vm)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                NavRail(vm, d)
                Content(vm, d, Modifier.weight(1f))
            }
        }
    }
}

private fun titleFor(vm: AppViewModel, searching: Boolean): Pair<String, String> = when {
    searching -> "نتائج" to "البحث"
    else -> when (vm.tab) {
        Tab.Home -> "أهلاً بعودتك،" to (if (vm.profileList.size > 1) vm.profile.name else "Nabd TV")
        Tab.Live -> "بث" to "مباشر"
        Tab.Movies -> "مكتبة" to "الأفلام"
        Tab.Series -> "عالم" to "المسلسلات"
        Tab.Guide -> "دليل" to "البرامج"
        Tab.Recordings -> "مركز" to "التسجيل"
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Content(vm: AppViewModel, d: Device, modifier: Modifier) {
    val searching = vm.query.isNotBlank() && (vm.tab == Tab.Home || vm.tab == Tab.Guide || vm.tab == Tab.Recordings)
    val (t, a) = titleFor(vm, searching)
    Column(modifier.padding(start = d.pad, end = d.pad, top = 12.dp)) {
        Header(vm, d, t, a)
        vm.update?.let { u ->
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(Radius.small)).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NIcon(Ic.Download, Accent, 20.dp)
                Text("إصدار جديد ${u.tag}", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                MBtn(vm.updateProgress?.let { "جارٍ التنزيل $it%" } ?: "تحديث الآن") { vm.installUpdate() }
            }
        }
        if (vm.loading || vm.vodLoading || vm.error != null || vm.healthStatus != null) {
            Spacer(Modifier.height(6.dp))
            if (vm.loading || vm.vodLoading) Text("جارٍ التحميل...", color = Accent, fontSize = 13.sp)
            vm.error?.let { Text("خطأ: $it", color = Danger, fontSize = 13.sp) }
            vm.healthStatus?.let { Text("فحص الروابط: $it", color = Accent, fontSize = 13.sp) }
        }
        Spacer(Modifier.height(10.dp))

        val key = if (searching) "search" else vm.tab.name
        AnimatedContent(
            targetState = key,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                (fadeIn(tween(240)) + scaleIn(initialScale = 0.98f, animationSpec = tween(240))) togetherWith fadeOut(tween(120))
            },
            label = "page"
        ) { k ->
            // Soft blur-in between pages (phones/tablets on Android 12+; skipped on TV boxes to keep them smooth).
            val blurR by transition.animateDp(label = "blur") { s -> if (s == EnterExitState.Visible) 0.dp else 12.dp }
            Box(Modifier.fillMaxSize().then(if (!d.tv && Build.VERSION.SDK_INT >= 31 && blurR > 0.dp) Modifier.blur(blurR) else Modifier)) {
                when (k) {
                    "search" -> SearchResults(vm, d)
                    Tab.Home.name -> HomeTab(vm, d)
                    Tab.Guide.name -> GuideTab(vm)
                    Tab.Recordings.name -> RecordingsTab(vm)
                    else -> BrowseTab(vm, d)
                }
            }
        }
    }
}

/* ───────── Home: stats, hero, shelves ───────── */

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatCard(label: String, value: String, ic: Ic, modifier: Modifier) {
    val shape = RoundedCornerShape(Radius.card)
    Row(
        modifier.softShadow(shape, 6.dp).glass(shape).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            NIcon(ic, Accent, 22.dp)
        }
        Column {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1)
            Text(label, fontSize = 12.sp, color = TextSecondary, maxLines = 1)
        }
    }
}

/** Ring gauge for the share of tested links that work (real data from the link checker). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HealthRing(good: Int, total: Int, modifier: Modifier) {
    val shape = RoundedCornerShape(Radius.card)
    val frac = if (total == 0) 0f else good.toFloat() / total
    val track = Hairline
    val color = if (frac >= 0.8f) Ok else if (frac >= 0.5f) Warn else Danger
    Row(
        modifier.softShadow(shape, 6.dp).glass(shape).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 6.dp.toPx()
                val inset = w / 2
                val sz = Size(size.width - w, size.height - w)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(w))
                if (total > 0) drawArc(color, -90f, 360f * frac, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
            }
            Text(if (total == 0) "—" else "${(frac * 100).toInt()}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
        Column {
            Text(if (total == 0) "—" else "$good/$total", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1)
            Text("روابط سليمة", fontSize = 12.sp, color = TextSecondary, maxLines = 1)
        }
    }
}

@Composable
private fun StatsRow(vm: AppViewModel, d: Device) {
    val recCount = vm.recordings.count { it.status == RecStatus.Done || it.status == RecStatus.Scheduled || it.status == RecStatus.Recording }
    val (good, total) = vm.healthSummary
    fun n(x: Int) = if (x == 0) "—" else "%,d".format(x)
    val cards = ArrayList<@Composable (Modifier) -> Unit>()
    cards += { m -> StatCard("قناة", n(vm.liveList.size), Ic.Live, m) }
    if (vm.hasXtream) {
        cards += { m -> StatCard("فيلم", n(vm.movieList.size), Ic.Movies, m) }
        cards += { m -> StatCard("مسلسل", n(vm.seriesList.size), Ic.Series, m) }
    }
    cards += { m -> StatCard("تسجيل", n(recCount), Ic.Rec, m) }
    cards += { m -> HealthRing(good, total, m) }
    val perRow = if (d.widthDp >= 900) cards.size else if (d.widthDp >= 560) 3 else 2
    Column(verticalArrangement = Arrangement.spacedBy(d.gap)) {
        cards.chunked(perRow).forEach { rowCards ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.gap)) {
                rowCards.forEach { c -> c(Modifier.weight(1f)) }
                repeat(perRow - rowCards.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeTab(vm: AppViewModel, d: Device) {
    val shelves = vm.shelves
    if (shelves.isEmpty() && vm.loading) { SkeletonGrid(d); return }
    if (shelves.isEmpty()) {
        Text("لا يوجد محتوى بعد. حدّث القائمة أو افتح الإعدادات.", color = TextSecondary, modifier = Modifier.padding(16.dp))
        return
    }
    SideEffect { vm.firstKey = if (vm.hero != null) "hero" else shelves.firstOrNull()?.items?.firstOrNull()?.let { it.url + it.id } }
    LazyColumn(
        state = vm.listState("home"),
        verticalArrangement = Arrangement.spacedBy(d.gap + 6.dp),
        contentPadding = PaddingValues(bottom = 24.dp, top = 4.dp)
    ) {
        item { StatsRow(vm, d) }
        vm.hero?.let { h -> item { Hero(vm, d, h) } }
        items(shelves, key = { it.title }) { s ->
            Column {
                Text(s.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.padding(bottom = 8.dp, start = 4.dp))
                LazyRow(
                    state = vm.listState("row:" + s.title),
                    horizontalArrangement = Arrangement.spacedBy(d.gap),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    items(s.items) { c ->
                        if (kindOf(c) == Kind.Live) LiveCard(vm, c, Modifier.width(d.shelfLive))
                        else PosterCard(vm, c, Modifier.width(d.shelfPoster))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Hero(vm: AppViewModel, d: Device, c: Channel) {
    val shape = RoundedCornerShape(28.dp)
    val play = remember { FocusRequester() }
    DisposableEffect(Unit) {
        vm.cardFocus["hero"] = play
        onDispose { if (vm.cardFocus["hero"] === play) vm.cardFocus.remove("hero") }
    }
    val isLive = kindOf(c) == Kind.Live
    Box(
        Modifier.fillMaxWidth().height(if (d.tv) 220.dp else if (d.portrait) 190.dp else 200.dp)
            .softShadow(shape, 14.dp).clip(shape).background(Brush.linearGradient(listOf(Navy, Accent.copy(alpha = 0.75f))))
    ) {
        if (!isLive) AsyncImage(model = c.logo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else AsyncImage(
            model = c.logo, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.align(Alignment.CenterEnd).padding(24.dp).size(if (d.tv) 120.dp else 84.dp).alpha(0.9f)
        )
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xEE0E1235), Color(0x330E1235)))))
        Column(Modifier.align(Alignment.CenterStart).padding(if (d.tv) 28.dp else 18.dp).fillMaxWidth(if (d.portrait) 0.85f else 0.6f)) {
            Text(c.name, fontSize = if (d.tv) 28.sp else 22.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val now = vm.nowNext(c).first
            Text(now?.title ?: Classifier.genre(c).label, fontSize = 14.sp, color = Color(0xFFC9CBFF), maxLines = 1)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                MBtn("▶ تشغيل", modifier = Modifier.focusRequester(play).onFocusChanged { if (it.isFocused) vm.lastFocusKey = "hero" }) { vm.open(c) }
                if (d.portrait && d.touch) {
                    IconBtn(Ic.Star, { vm.toggleFav(c) }, tint = if (c.url in vm.favs) Warn else Ink)
                    IconBtn(Ic.Dice, { vm.tonight() })
                } else {
                    MBtn(if (c.url in vm.favs) "★ في المفضلة" else "☆ مفضلة", filled = false) { vm.toggleFav(c) }
                    MBtn("🎲 ماذا أشاهد؟", filled = false) { vm.tonight() }
                }
            }
        }
    }
}

/** Pulsing placeholders while the first list loads. */
@Composable
private fun SkeletonGrid(d: Device) {
    val a by rememberInfiniteTransition(label = "sk").animateFloat(
        0.35f, 0.9f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "skA"
    )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(d.liveMin), userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(d.gap), verticalArrangement = Arrangement.spacedBy(d.gap)
    ) {
        items(12) { Box(Modifier.fillMaxWidth().height(120.dp).alpha(a).glass(RoundedCornerShape(Radius.card))) }
    }
}

/* ───────── Live / Movies / Series browsing ───────── */

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GroupItem(vm: AppViewModel, g: String, modifier: Modifier = Modifier) {
    val sel = vm.group == g
    Focusable(selected = sel, onClick = { vm.group = g }, onLongClick = { vm.groupMenu(g) }, modifier = modifier) {
        Text(
            vm.groupLabel(g), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
            color = if (sel) Accent else Ink,
            modifier = Modifier.padding(horizontal = 14.dp).heightIn(min = LocalDevice.current.control).wrapContentHeight(Alignment.CenterVertically)
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
        if (items.isEmpty()) Text("لا توجد نتائج", color = TextSecondary, fontSize = 15.sp)
        LazyVerticalGrid(
            state = vm.gridState(vm.tab.name + "|" + vm.group),
            columns = GridCells.Adaptive(if (poster) d.posterMin else d.liveMin),
            horizontalArrangement = Arrangement.spacedBy(d.gap), verticalArrangement = Arrangement.spacedBy(d.gap),
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 20.dp), modifier = modifier
        ) {
            items(items, key = { it.url + it.id }) { ch -> if (poster) PosterCard(vm, ch) else LiveCard(vm, ch) }
        }
    }

    if (d.twoPane) {
        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.width(d.sideList), state = vm.listState("groups:" + vm.tab.name),
                verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp)
            ) {
                items(vm.groups, key = { it }) { g -> GroupItem(vm, g, Modifier.fillMaxWidth()) }
                item {
                    if (vm.tab == Tab.Live) {
                        Spacer(Modifier.height(8.dp))
                        MBtn("▦ مشاهدة متعددة (${vm.multi.size})", filled = false, modifier = Modifier.fillMaxWidth()) { vm.openMulti() }
                    }
                }
            }
            Spacer(Modifier.width(d.gap + 4.dp))
            Column(Modifier.weight(1f)) {
                Text("${items.size}", color = TextSecondary, fontSize = 12.sp)
                Grid(Modifier.fillMaxSize())
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            LazyRow(
                state = vm.listState("chips:" + vm.tab.name),
                horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
            ) {
                items(vm.groups, key = { it }) { g -> GroupItem(vm, g) }
                if (vm.tab == Tab.Live) item { TabChip("▦ متعدد (${vm.multi.size})", false) { vm.openMulti() } }
            }
            Grid(Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchResults(vm: AppViewModel, d: Device) {
    val r = vm.searchResults
    SideEffect { vm.firstKey = r.firstOrNull()?.let { it.url + it.id } }
    Column {
        Text("نتائج البحث: ${r.size}", color = TextSecondary, fontSize = 12.sp)
        LazyVerticalGrid(
            state = vm.gridState("search"),
            columns = GridCells.Adaptive(d.liveMin),
            horizontalArrangement = Arrangement.spacedBy(d.gap), verticalArrangement = Arrangement.spacedBy(d.gap),
            contentPadding = PaddingValues(4.dp)
        ) {
            items(r, key = { it.url + it.id }) { c -> if (kindOf(c) == Kind.Live) LiveCard(vm, c) else PosterCard(vm, c) }
        }
    }
}

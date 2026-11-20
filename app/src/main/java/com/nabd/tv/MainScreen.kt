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
import androidx.compose.ui.draw.rotate
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

/* ───────── Home ───────── */

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeTab(vm: AppViewModel, d: Device) {
    val shelves = vm.shelves
    if (shelves.isEmpty() && vm.loading) { SkeletonGrid(d); return }
    if (shelves.isEmpty()) {
        Text("لا يوجد محتوى بعد. حدّث القائمة أو افتح الإعدادات.", color = TextSecondary, modifier = Modifier.padding(16.dp))
        return
    }
    val wide = !d.portrait
    SideEffect { vm.firstKey = shelves.firstOrNull()?.items?.firstOrNull()?.let { shelves.first().title + "|" + it.url + it.id } }

    // Spotlight: until the user puts focus on a card, the hero cycles through a few featured titles.
    val spotlight = remember(shelves) {
        shelves.filter { it.style == ShelfStyle.Poster }.flatMap { it.items.take(2) }.distinctBy { it.url }.take(6)
            .ifEmpty { listOf(shelves.first().items.first()) }
    }
    var spot by remember { mutableIntStateOf(0) }
    val userFocus = vm.homeFocus
    LaunchedEffect(userFocus == null, spotlight.size) {
        if (userFocus == null && spotlight.size > 1) while (true) { kotlinx.coroutines.delay(7000); spot = (spot + 1) % spotlight.size }
    }
    val focus = userFocus ?: spotlight[spot % spotlight.size]
    LaunchedEffect(focus.url) { kotlinx.coroutines.delay(350); vm.loadHomeInfo(focus) }

    val genres = remember(vm.movieList) {
        vm.movieList.groupingBy { it.group }.eachCount().entries.sortedByDescending { it.value }.take(12).map { it.key }
    }
    val fade = if (Ui.dark) Color(0xFF0B0D1F) else Color(0xFFF1F2FD)
    val heroH = (d.heightDp * (if (d.tv) 0.42f else 0.44f)).dp
    Box(Modifier.fillMaxSize()) {
        if (wide) {
            // The backdrop of the focused title fills the whole page; the shelves float over its lower part.
            androidx.compose.animation.Crossfade(vm.homeInfo?.backdrop ?: focus.logo, animationSpec = tween(500), label = "bd") { img ->
                AsyncImage(model = img, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF20E1235), Color(0x990E1235), Color(0x1A0E1235)))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.30f to Color.Transparent, 0.75f to fade.copy(alpha = 0.88f), 1f to fade)))
        }
        Column(Modifier.fillMaxSize()) {
            if (wide) Row(Modifier.fillMaxWidth().height(heroH), verticalAlignment = Alignment.CenterVertically) {
                HomeHero(vm, focus, vm.homeInfo, d, Modifier.weight(1f).fillMaxHeight())
                // Floating poster of the focused title, slightly tilted, so the right side of the hero is not empty.
                AsyncImage(
                    model = focus.logo, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(end = 28.dp).fillMaxHeight(0.9f).aspectRatio(2f / 3f)
                        .rotate(-3f).clip(RoundedCornerShape(14.dp))
                        .border(2.dp, Color(0x66FFFFFF), RoundedCornerShape(14.dp)).background(Surf)
                )
            }
            LazyColumn(
                state = vm.listState("home"), modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(d.gap),
                contentPadding = PaddingValues(bottom = 16.dp, top = 2.dp)
            ) {
                if (!wide) item(key = "featured") { FeaturedBanner(vm, focus, vm.homeInfo) }
                if (genres.size > 1) item(key = "genres") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
                        items(genres) { g -> GenreChip(vm.groupLabel(g)) { vm.selectTab(Tab.Movies); vm.group = g } }
                    }
                }
                items(shelves, key = { it.title }) { s ->
                    Column {
                        ShelfHeader(s, d)
                        LazyRow(
                            state = vm.listState("row:" + s.title),
                            horizontalArrangement = Arrangement.spacedBy(if (s.ranked) d.gap + 14.dp else d.gap),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            itemsIndexed(s.items) { i, c ->
                                when (s.style) {
                                    ShelfStyle.Wide -> WideCard(vm, d, s, c)
                                    ShelfStyle.Live -> LiveShelfCard(vm, d, s, c)
                                    ShelfStyle.Poster -> HomeCard(vm, d, s, c, i + 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Pill shortcut to a movie category. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GenreChip(text: String, onClick: () -> Unit) {
    FlatFocusable(onClick = onClick, zoom = 1.05f) { f ->
        Text(
            text, maxLines = 1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (f) OnAccent else Ink,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (f) Accent else GlassFill).padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ShelfHeader(s: Shelf, d: Device) {
    Row(Modifier.padding(bottom = 4.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(if (d.tv) 22.dp else 18.dp).clip(RoundedCornerShape(2.dp)).background(Accent))
        Spacer(Modifier.width(8.dp))
        Text(s.title, fontSize = if (d.tv) 21.sp else 18.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.width(8.dp))
        Text("${s.items.size}", fontSize = 12.sp, color = TextSecondary)
    }
}

/** Big banner at the top of the phone (portrait) home screen; TV and tablets use the backdrop hero instead. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FeaturedBanner(vm: AppViewModel, c: Channel, info: VodInfo?) {
    Box(
        Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(Radius.card))
            .clickable { vm.open(c) }
    ) {
        AsyncImage(model = info?.backdrop ?: c.logo, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE60E1235))))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(c.name, fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ratingBadge(c)?.let { HeroChip("★ $it", Color(0xFFFFC857), Color(0xFF0E1235)) }
                info?.year?.takeIf { it.isNotBlank() }?.let { HeroChip(it) }
                info?.genre?.takeIf { it.isNotBlank() }?.split(',', '،', '/')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(2)?.forEach { HeroChip(it) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeCard(vm: AppViewModel, d: Device, s: Shelf, ch: Channel, rank: Int) {
    val key = s.title + "|" + ch.url + ch.id
    val fr = rememberCardFocus(vm, key)
    val shape = RoundedCornerShape(12.dp)
    Row(verticalAlignment = Alignment.Bottom) {
        if (s.ranked) {
            Text(
                "$rank", fontSize = if (d.tv) 64.sp else 52.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false,
                style = androidx.compose.ui.text.TextStyle(
                    color = Accent, drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                ),
                modifier = Modifier.offset(y = 4.dp)
            )
        }
        FlatFocusable(
            onClick = { vm.open(ch) }, onLongClick = { vm.channelMenu(ch) },
            onFocused = { vm.lastFocusKey = key; vm.homeFocus = ch }, focusRequester = fr,
            modifier = Modifier.width(if (d.tv) 138.dp else 118.dp)
        ) { f ->
            Box(Modifier.clip(shape).then(if (f) Modifier.border(3.dp, Accent, shape) else Modifier)) {
                AsyncImage(
                    model = ch.logo, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Surf)
                )
                ratingBadge(ch)?.let { r ->
                    Row(
                        Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(10.dp))
                            .background(Color(0xCC0E1235)).padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("★", fontSize = 10.sp, color = Color(0xFFFFC857)); Spacer(Modifier.width(3.dp))
                        Text(r, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
            Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = if (f) Accent else Ink, modifier = Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp))
        }
    }
}

/** Landscape card used for "continue watching": poster cropped to 16:9 with the title and a progress bar. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WideCard(vm: AppViewModel, d: Device, s: Shelf, ch: Channel) {
    val key = s.title + "|" + ch.url + ch.id
    val fr = rememberCardFocus(vm, key)
    val progress = vm.history.firstOrNull { it.url == ch.url }?.progress ?: 0f
    val shape = RoundedCornerShape(12.dp)
    FlatFocusable(
        onClick = { vm.open(ch) }, onLongClick = { vm.channelMenu(ch) },
        onFocused = { vm.lastFocusKey = key; vm.homeFocus = ch }, focusRequester = fr,
        modifier = Modifier.width(if (d.tv) 240.dp else 200.dp)
    ) { f ->
        Box(Modifier.clip(shape).then(if (f) Modifier.border(3.dp, Accent, shape) else Modifier)) {
            AsyncImage(
                model = ch.logo, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Surf)
            )
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD90E1235))))
            Text(
                ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
            )
            if (progress > 0.02f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceAtMost(1f)).background(Accent))
            }
        }
    }
}

/** Live channel card for the home screen: logo, name and what is on now with a progress bar. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveShelfCard(vm: AppViewModel, d: Device, s: Shelf, ch: Channel) {
    val key = s.title + "|" + ch.url + ch.id
    val fr = rememberCardFocus(vm, key)
    val (now, _) = vm.nowNext(ch)
    val p = now?.let { ((System.currentTimeMillis() - it.start).toFloat() / (it.stop - it.start).coerceAtLeast(1L)).coerceIn(0f, 1f) } ?: 0f
    val shape = RoundedCornerShape(12.dp)
    FlatFocusable(
        onClick = { vm.open(ch) }, onLongClick = { vm.channelMenu(ch) },
        onFocused = { vm.lastFocusKey = key }, focusRequester = fr,
        modifier = Modifier.width(if (d.tv) 170.dp else 140.dp)
    ) { f ->
        Column(
            Modifier.fillMaxWidth().clip(shape)
                .background(if (f) GlassFocus else Color.Transparent)
                .then(if (f) Modifier.border(3.dp, Accent, shape) else Modifier)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.fillMaxWidth().height(if (d.tv) 52.dp else 44.dp), contentAlignment = Alignment.Center) {
                AsyncImage(model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(4.dp))
            Text("● مباشر", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF5A5F))
            Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink)
            Text(now?.title ?: " ", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = Accent)
            if (now != null) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33808080))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(Accent))
                }
            }
        }
    }
}

/** Title block of the focused item over the backdrop (TV / tablet / landscape), with play and favourite actions. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeHero(vm: AppViewModel, c: Channel, info: VodInfo?, d: Device, modifier: Modifier) {
    Column(modifier.padding(start = 8.dp, top = 4.dp), verticalArrangement = Arrangement.Center) {
        Text(c.name, fontSize = if (d.tv) 34.sp else 28.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ratingBadge(c)?.let { HeroChip("★ $it", Color(0xFFFFC857), Color(0xFF0E1235)) }
            info?.year?.takeIf { it.isNotBlank() }?.let { HeroChip(it) }
            info?.duration?.takeIf { it.isNotBlank() }?.let { HeroChip(it) }
            info?.genre?.takeIf { it.isNotBlank() }?.split(',', '،', '/')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(3)?.forEach { HeroChip(it) }
        }
        info?.plot?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = if (d.tv) 14.sp else 13.sp, lineHeight = 20.sp, color = Color(0xFFE0E2FF), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        val credits = listOfNotNull(
            info?.director?.takeIf { it.isNotBlank() }?.let { "إخراج: $it" },
            info?.cast?.takeIf { it.isNotBlank() }?.split(',', '،')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(3)?.joinToString("، ")?.let { "بطولة: $it" }
        ).joinToString("   •   ")
        if (credits.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(credits, fontSize = 12.sp, color = Color(0xFFC9CCFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MBtn("▶  تشغيل") { vm.open(c) }
            MBtn(if (c.url in vm.favs) "★  في المفضلة" else "☆  أضف للمفضلة", filled = false) { vm.toggleFav(c) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroChip(t: String, bg: Color = Color(0x33FFFFFF), fg: Color = Color.White) {
    Text(t, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp))
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
    val progs = vm.progResults
    SideEffect { vm.firstKey = if (progs.isNotEmpty()) null else r.firstOrNull()?.let { it.url + it.id } }
    Column {
        Text("نتائج البحث: ${r.size + progs.size}", color = TextSecondary, fontSize = 12.sp)
        LazyVerticalGrid(
            state = vm.gridState("search"),
            columns = GridCells.Adaptive(d.liveMin),
            horizontalArrangement = Arrangement.spacedBy(d.gap), verticalArrangement = Arrangement.spacedBy(d.gap),
            contentPadding = PaddingValues(4.dp)
        ) {
            if (progs.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "prog-head") {
                    Text("برامج في الدليل (${progs.size})", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                items(progs, key = { "p" + it.first.url + it.second.start }, span = { GridItemSpan(maxLineSpan) }) { (c, p) ->
                    ProgRow(vm, c, p)
                }
                if (r.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "prog-tail") {
                    Text("القنوات والأفلام والمسلسلات (${r.size})", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
    Focusable(
        onClick = {
            if (airing) vm.play(c)
            else vm.menu = MenuRequest(p.title, listOf("▶ مشاهدة القناة" to { vm.play(c) }, "⏺ تسجيل البرنامج" to { vm.record(c, p) }))
        },
        modifier = Modifier.fillMaxWidth(), zoom = 1.02f
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (airing) "الآن" else dayHm(p.start), color = if (airing) Accent else TextSecondary,
                fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(84.dp), maxLines = 1
            )
            Column(Modifier.weight(1f)) {
                Text(p.title, fontSize = 14.sp, maxLines = 1, color = Ink)
                Text(c.name, fontSize = 11.sp, maxLines = 1, color = TextSecondary)
            }
        }
    }
}

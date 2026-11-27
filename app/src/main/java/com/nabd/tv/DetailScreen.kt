package com.nabd.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(vm: AppViewModel) {
    val c = vm.detail ?: return
    val isSeries = kindOf(c) == Kind.Series
    val info = vm.info
    BackHandler { vm.closeDetail() }
    // Full-page details: opened by pressing the synopsis box; Back closes it (registered later, so it wins over closeDetail).
    var showFull by remember { mutableStateOf(false) }
    BackHandler(enabled = showFull) { showFull = false }
    val synopsisFocus = remember { FocusRequester() }
    val fullFocus = remember { FocusRequester() }
    var fullWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(showFull) {
        if (showFull) { fullWasOpen = true; runCatching { fullFocus.requestFocus() } }
        else if (fullWasOpen) { fullWasOpen = false; runCatching { synopsisFocus.requestFocus() } }
    }
    val playFocus = remember { FocusRequester() }
    val favFocus = remember { FocusRequester() }   // first button of a series page (no Play button there)
    val d = LocalDevice.current
    val edge = edgePad(d)
    val narrow = d.widthDp < 600
    val shortScreen = d.heightDp < 440
    LaunchedEffect(c.url, info?.backdrop, info?.poster) { ArtStore.put(c, info?.poster, info?.backdrop) }
    val palette = rememberArtworkPalette(info?.backdrop ?: c.logo)
    val typeLabel = tr(if (isSeries) R.string.detail_type_series else R.string.detail_type_movie)

    val scroll = rememberScrollState()
    val heroFocus = remember { FocusRequester() }
    // A new title starts at the top; the neighbours' artwork and logos are fetched ahead so stepping to them shows no wait.
    LaunchedEffect(c.url) {
        scroll.scrollTo(0)
        vm.neighbors().filterNotNull().forEach { n -> launch { runCatching { ArtStore.resolve(vm, n); Logos.get(n.name, kindOf(n), null) } } }
    }
    if (d.tv) LaunchedEffect(Unit) { runCatching { heroFocus.requestFocus() } }   // invisible focus holder: left / right work at once, Down goes to the buttons
    val blurStep by remember { derivedStateOf { (scroll.value / 70).coerceIn(0, 5) } }
    val logoFile = rememberTitleLogo(c.name, kindOf(c), null)   // year from the title only: a hint that arrives later would change the key and swap the logo
    val logoPal = rememberArtworkPalette(logoFile?.let { "file://" + it.absolutePath })
    // Buttons take the logo's dominant colour; no logo (or a colourless one) falls back to the artwork palette, kept readable by buttonTone.
    val btnPal = if (logoFile != null) logoPal else palette

    // 0 at the top of the page -> 1 once the hero has mostly scrolled away: the hero logo fades out while the same logo fades in beside the buttons.
    val heroPx = with(androidx.compose.ui.platform.LocalDensity.current) { ((d.heightDp - 8).coerceAtLeast(240)).dp.toPx() }
    val logoH = if (narrow) 48.dp else if (d.tv) 72.dp else 60.dp
    // One logo: it travels from the middle of the artwork to the empty slot beside the buttons while the page scrolls, then rides with the page.
    var heroC by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var slotC by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    // Sizes of the hero logo and of the empty slot it travels to: a long title / wide logo is scaled down to fit the slot instead of spilling off the screen.
    var heroSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var slotSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    fun fitScale(): Float {
        if (heroSize.width <= 0 || heroSize.height <= 0 || slotSize.width <= 0 || slotSize.height <= 0) return 1f
        return minOf(1f, slotSize.width.toFloat() / heroSize.width, slotSize.height.toFloat() / heroSize.height)
    }
    fun centre(c: androidx.compose.ui.layout.LayoutCoordinates) = c.positionInRoot() + androidx.compose.ui.geometry.Offset(c.size.width / 2f, c.size.height / 2f)
    fun logoProgress(): Float = (scroll.value / (heroPx * 0.45f)).coerceIn(0f, 1f)
    val titleLogo: @Composable () -> Unit = {
        TitleLogo(
            c.name, kindOf(c), null,
            height = logoH,
            alignment = Alignment.Center
        ) {
            Text(
                c.name, style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.65f), Offset(0f, 3f), 14f)), fontSize = if (narrow) 18.sp else if (d.tv) 26.sp else 22.sp,
                lineHeight = if (d.tv) 32.sp else 28.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                // Same width cap as a logo, so a long title wraps to two lines instead of becoming one very wide line.
                modifier = Modifier.widthIn(max = if (d.tv) 380.dp else 280.dp)
            )
        }
    }

    Box(Modifier.fillMaxSize().appBackground()) {
        // Full-bleed backdrop: wide artwork to the screen edges, dissolving into the page (no frame, no card).
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.72f).fadeOutBottom(0.55f)) {
            // Phone held upright: the portrait poster fills the tall page; wide screens (landscape phone, tablet, TV): the wide backdrop.
            val upright = d.portrait && !d.tv
            // Same adaptive image system as the home screen: poster when upright, backdrop when wide; either falls back to the other.
            AdaptiveArtImage(vm, c, landscape = !upright, maxWaitMs = 6000, modifier = Modifier.fillMaxSize().then(if (blurStep > 0) Modifier.blur((blurStep * 5).dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle) else Modifier))
            if (blurStep > 0) Box(Modifier.fillMaxSize().background(Scrim.copy(alpha = blurStep * 0.1f)))
            Box(Modifier.fillMaxSize().textSideScrim(if (narrow) 0.35f else 0.40f))
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.0f to Scrim.copy(alpha = 0.20f), 0.30f to Color.Transparent, 0.62f to Scrim.copy(alpha = 0.12f), 1f to Scrim.copy(alpha = 0.55f))
                )
            )
        }

        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = edge.start, end = edge.end), verticalAlignment = Alignment.CenterVertically) {
                if (!d.tv) MBtn(tr(R.string.detail_back), filled = false) { vm.closeDetail() }
                Spacer(Modifier.weight(1f))
                Text(
                    typeLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(palette.primary.copy(alpha = 0.55f))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }

            // Hero: only the artwork with the logo (or title) and the info chips. It fills the screen, so the buttons start below the fold.
            Column(
                Modifier.fillMaxWidth().height((d.heightDp - 8).coerceAtLeast(240).dp).zIndex(1f)
                    .pointerInput(c.url) {
                        // Phone: a horizontal swipe on the artwork moves to the previous / next title.
                        var dx = 0f
                        detectHorizontalDragGestures(onDragStart = { dx = 0f }, onHorizontalDrag = { _, a -> dx += a }, onDragEnd = {
                            if (kotlin.math.abs(dx) > 120f) vm.stepDetail(if (dx < 0) 1 else -1)
                        })
                    }
                    .focusRequester(heroFocus).focusable()
                    .onPreviewKeyEvent { e ->
                        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && (e.key == androidx.compose.ui.input.key.Key.DirectionLeft || e.key == androidx.compose.ui.input.key.Key.DirectionRight)) {
                            vm.stepDetail(if (e.key == androidx.compose.ui.input.key.Key.DirectionRight) 1 else -1); true
                        } else if (d.tv && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.DirectionDown) {
                            // Down from the artwork always lands on the first (leftmost) button: Play, or Favourite on a series page.
                            runCatching { (if (isSeries) favFocus else playFocus).requestFocus() }.isSuccess
                        } else false
                    }
                    .padding(horizontal = maxOf(edge.start, edge.end)),
                verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // The logo / title never disappears: it rides up with the scroll and stays pinned at the top until the end of the page.
                // The logo / title scrolls away with the page (it is no longer pinned).
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.onGloballyPositioned { heroC = centre(it); heroSize = it.size }
                        .graphicsLayer {
                            val pr = logoProgress()
                            translationX = (slotC.x - heroC.x) * pr; translationY = (slotC.y - heroC.y) * pr
                            // Shrink towards the slot's size while travelling (both are centred, so scaling about the centre keeps it inside the slot).
                            val sc = 1f + (fitScale() - 1f) * pr
                            scaleX = sc; scaleY = sc
                        }
                ) { titleLogo() }
                Spacer(Modifier.height(if (d.tv) 150.dp else 110.dp))   // lifts the logo / title a little higher on the artwork
            }
            Spacer(Modifier.height(14.dp))
            val focusMgr = androidx.compose.ui.platform.LocalFocusManager.current
            val scope = rememberCoroutineScope()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            // Buttons, info chips and the synopsis box sit on the physical left of the page (same in Arabic and English).
            CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                Row(Modifier.fillMaxWidth().padding(horizontal = maxOf(edge.start, edge.end)), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    if (narrow) Box(Modifier.fillMaxWidth().padding(bottom = 10.dp).height(logoH).onGloballyPositioned { slotC = centre(it); slotSize = it.size })
                    FlowRow(
                        Modifier.onPreviewKeyEvent { e ->
                            // Up from the buttons: back to the artwork (nothing focused), so the next Down reveals the buttons again.
                            if (e.key == androidx.compose.ui.input.key.Key.DirectionUp && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                                if (d.tv) runCatching { heroFocus.requestFocus() } else focusMgr.clearFocus(); scope.launch { scroll.animateScrollTo(0) }; true
                            } else false
                        },
                        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.Start), verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!isSeries) {
                            val h = vm.store.historyOf(c.url)
                            val label = if (h != null && !h.finished && h.position > 10_000) tr(R.string.detail_resume, h.position / 60000) else tr(R.string.detail_play)
                            MBtn(label, tint = buttonTone(btnPal.primary), modifier = Modifier.focusRequester(playFocus)) { vm.play(c) }
                            if (DownloadRules.isDownloadable(c.url)) DownloadButton(vm, c.name, c.url, info?.poster ?: c.logo, btnPal.accent)
                        }
                        MBtn(if (c.url in vm.favs) tr(R.string.detail_fav_on) else tr(R.string.detail_fav_off), filled = false, tint = btnPal.accent, modifier = if (isSeries) Modifier.focusRequester(favFocus) else Modifier) { vm.toggleFav(c) }
                        MBtn((if (c.url in vm.liked) "✓ " else "") + "👍", filled = false, tint = btnPal.accent) { vm.rate(c, true) }
                        MBtn((if (c.url in vm.disliked) "✓ " else "") + "👎", filled = false, tint = btnPal.accent) { vm.rate(c, false) }
                        val trailerId = info?.trailer.orEmpty()
                        MBtn(if (trailerId.isNotBlank()) tr(R.string.detail_trailer) else tr(R.string.detail_trailer_search), filled = false, tint = btnPal.accent) {
                            val url = if (trailerId.isNotBlank()) "https://www.youtube.com/watch?v=" + android.net.Uri.encode(trailerId)
                            else "https://www.youtube.com/results?search_query=" + android.net.Uri.encode(c.name + " trailer")
                            try {
                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: Exception) { vm.say(S(R.string.detail_no_viewer)) }
                        }
                    }
                    val versions by produceState(emptyList<Channel>(), c.url) { value = withContext(Dispatchers.Default) { vm.versionsOf(c) } }
                    if (versions.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        VersionChips(vm, c, versions)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.Start), verticalAlignment = Alignment.CenterVertically) {
                        (if (vm.store.tmdbKey.isNotBlank()) info?.rating?.takeIf { it.isNotBlank() } else (info?.rating?.takeIf { it.isNotBlank() } ?: ratingBadge(c)))?.let {
                            MetaChip("★ $it", fg = Color(0xFFFFD27A), bg = Color(0x33FFB74D))
                        }
                        info?.year?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
                        info?.duration?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
                        info?.genre?.takeIf { it.isNotBlank() }?.split(',', '،', '/')?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }?.take(if (narrow) 1 else 2)?.forEach { MetaChip(it) }
                        // Country of origin and original language (phones: just one chip so the row still fits).
                        val country = info?.country?.takeIf { it.isNotBlank() }?.let { Origin.countryName(it, Lang.code) }
                        val language = info?.language?.takeIf { it.isNotBlank() }?.let { Origin.languageName(it, Lang.code) }
                        if (narrow) (country ?: language)?.let { MetaChip(it) }
                        else { country?.let { MetaChip(it) }; language?.let { MetaChip(it) } }
                    }
                    if (isSeries) vm.nextAir?.let { n -> Spacer(Modifier.height(8.dp)); NextAirChips(n) }
                    if (info != null && info.plot.isNotBlank() && !(isSeries && shortScreen)) {
                        Spacer(Modifier.height(10.dp))
                        val boxShape = RoundedCornerShape(14.dp)
                        // Synopsis in its own box, under the buttons. Pressing it opens the full details page.
                        var synFocused by remember { mutableStateOf(false) }
                        var synCut by remember { mutableStateOf(false) }
                        Box(
                            Modifier.fillMaxWidth(if (narrow) 1f else 0.62f).widthIn(max = 620.dp)
                                .focusRequester(synopsisFocus)
                                .onFocusChanged { synFocused = it.isFocused }
                                .clip(boxShape).background(Color.Black.copy(alpha = 0.38f))
                                .border(if (synFocused) 2.dp else 1.dp, if (synFocused) Color.White else Color.White.copy(alpha = 0.12f), boxShape)
                                .clickable { showFull = true }
                                .padding(horizontal = 14.dp, vertical = 11.dp)
                        ) {
                            Column {
                                Text(
                                    info.plot, textAlign = androidx.compose.ui.text.style.TextAlign.Left, fontSize = if (narrow) 12.sp else 13.sp, lineHeight = 19.sp,
                                    color = Color(0xFFE3E6F0), maxLines = if (narrow) 4 else 5, overflow = TextOverflow.Ellipsis,
                                    onTextLayout = { synCut = it.hasVisualOverflow }
                                )
                                if (synCut) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(tr(R.string.detail_more), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = btnPal.accent, textAlign = androidx.compose.ui.text.style.TextAlign.Left)
                                }
                            }
                        }
                    }
                    if (info != null && info.cast.isNotBlank() && !shortScreen) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr(R.string.detail_cast, info.cast), fontSize = 11.5.sp, color = Color(0xFFC3C8D8), textAlign = androidx.compose.ui.text.style.TextAlign.Left,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(if (narrow) 1f else 0.62f)
                        )
                    }
                }
                // Wide screens: empty slot on the right, beside the buttons and the synopsis; the logo travels here as the page scrolls.
                if (!narrow) Box(Modifier.width(if (d.tv) 300.dp else 240.dp).padding(start = 16.dp).height(logoH).onGloballyPositioned { slotC = centre(it); slotSize = it.size })
                }
            }
            Spacer(Modifier.height(10.dp))

            if (isSeries) {
                Row(
                    Modifier.fillMaxWidth().padding(start = edge.start, end = edge.end, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(tr(R.string.detail_episodes), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
                    if (vm.episodes.isNotEmpty()) Text(tr(R.string.detail_episode_count, vm.episodes.size), fontSize = 12.sp, color = TextMuted)
                }
                // The gallery spans the full width (cards scroll to the screen edge) and wraps its own height.
                Episodes(vm, c, d, edge)
            }
            if (vm.franchiseWorks.isNotEmpty()) WorksRow(
                vm, edge, vm.franchiseWorks, R.string.detail_cast_works, "fr|",
                tr(R.string.detail_franchise, vm.franchiseName, vm.franchiseWorks.size, vm.franchiseTotal)
            )
            CastRow(info, edge)
            WorksRow(vm, edge)
            WorksRow(vm, edge, vm.similarWorks, R.string.detail_similar, "sim|")
            Spacer(Modifier.height(24.dp))
        }
        if (showFull && info != null) FullDetails(c.name, typeLabel, info, fullFocus, edge, narrow) { showFull = false }
    }
}

/** Full-page details (complete synopsis, director, cast, genre): opened from the synopsis box. D-pad Up/Down scroll, OK / Back close. */
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FullDetails(name: String, typeLabel: String, info: VodInfo, focus: FocusRequester, edge: EdgePad, narrow: Boolean, onClose: () -> Unit) {
    val st = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(androidx.compose.ui.platform.LocalDensity.current) { 140.dp.toPx() }
    Box(
        Modifier.fillMaxSize().background(Scrim.copy(alpha = 0.97f))
            .pointerInput(Unit) { detectTapGestures { } }   // swallow touches so nothing behind reacts
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    androidx.compose.ui.input.key.Key.DirectionDown -> { scope.launch { st.animateScrollBy(step) }; true }
                    androidx.compose.ui.input.key.Key.DirectionUp -> { scope.launch { st.animateScrollBy(-step) }; true }
                    androidx.compose.ui.input.key.Key.DirectionCenter, androidx.compose.ui.input.key.Key.Enter -> { onClose(); true }
                    androidx.compose.ui.input.key.Key.DirectionLeft, androidx.compose.ui.input.key.Key.DirectionRight -> true
                    else -> false
                }
            }
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(st)
                .padding(start = maxOf(edge.start, 20.dp), end = maxOf(edge.end, 20.dp), top = 18.dp, bottom = 28.dp)
                .widthIn(max = 860.dp).align(Alignment.TopCenter)
        ) {
            Text(name, fontSize = if (narrow) 20.sp else 26.sp, lineHeight = if (narrow) 26.sp else 32.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                MetaChip(typeLabel)
                info.rating.takeIf { it.isNotBlank() }?.let { MetaChip("★ $it", fg = Color(0xFFFFD27A), bg = Color(0x33FFB74D)) }
                info.year.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
                info.duration.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
                info.genre.split(',', '،', '/').map { it.trim() }.filter { it.isNotEmpty() }.forEach { MetaChip(it) }
                info.country.takeIf { it.isNotBlank() }?.let { MetaChip(Origin.countryName(it, Lang.code)) }
                info.language.takeIf { it.isNotBlank() }?.let { MetaChip(Origin.languageName(it, Lang.code)) }
            }
            Spacer(Modifier.height(16.dp))
            Text(info.plot, fontSize = if (narrow) 14.sp else 16.sp, lineHeight = if (narrow) 23.sp else 27.sp, color = Color(0xFFE9ECF5))
            if (info.director.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(tr(R.string.detail_director, info.director), fontSize = 13.sp, lineHeight = 20.sp, color = Color(0xFFC3C8D8))
            }
            if (info.cast.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(tr(R.string.detail_cast, info.cast), fontSize = 13.sp, lineHeight = 20.sp, color = Color(0xFFC3C8D8))
            }
            Spacer(Modifier.height(20.dp))
            MBtn(tr(R.string.detail_back), filled = false) { onClose() }
        }
    }
}

private fun castPeople(info: VodInfo?): List<Person> {
    if (info == null) return emptyList()
    if (info.people.isNotEmpty()) return info.people
    return info.cast.split(',', '،', '/', '|', ';').map { it.trim() }.filter { it.length > 1 }.distinct().take(12).map { Person(it) }
}

/** One round photo per actor (initials when there is no photo) with the name below. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CastRow(info: VodInfo?, edge: EdgePad) {
    val people = castPeople(info)
    if (people.isEmpty()) return
    Spacer(Modifier.height(14.dp))
    Text(tr(R.string.detail_cast_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.padding(start = edge.start, end = edge.end, bottom = 8.dp))
    LazyRow(contentPadding = PaddingValues(start = edge.start, end = edge.end), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(people.size) { i ->
            val p = people[i]
            Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(SurfHigh).border(1.dp, Hairline, CircleShape), contentAlignment = Alignment.Center) {
                    if (p.photo != null) AsyncImage(model = p.photo, contentDescription = p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Text(p.name.trim().take(1), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                }
                Spacer(Modifier.height(5.dp))
                Text(p.name, fontSize = 11.sp, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 14.sp)
            }
        }
    }
}

/** Library titles that feature the same lead actors. */
@Composable
private fun WorksRow(
    vm: AppViewModel, edge: EdgePad,
    works: List<Channel> = vm.castWorks, titleRes: Int = R.string.detail_cast_works, keyPrefix: String = "cw|", titleText: String? = null
) {
    if (works.isEmpty()) return
    val d = LocalDevice.current
    Spacer(Modifier.height(16.dp))
    Text(titleText ?: tr(titleRes), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.padding(start = edge.start, end = edge.end, bottom = 8.dp))
    LazyRow(contentPadding = PaddingValues(start = edge.start, end = edge.end), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(works.size, key = { works[it].url + works[it].id }) { i -> PosterCard(vm, works[i], Modifier.width(if (!d.portrait) 200.dp else 112.dp), focusKey = keyPrefix + works[i].url, adaptive = true) }
    }
}

/**
 * Horizontal episode gallery: landscape 16:9 stills side by side, with the episode number, title and progress
 * BELOW each image. D-pad left/right moves between cards, touch scrolls naturally. LazyRow only composes
 * (and therefore only loads images for) the cards near the viewport.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Episodes(vm: AppViewModel, s: Channel, d: Device, edge: EdgePad) {
    val seasons = vm.episodes.map { it.season }.distinct()
    if (seasons.size > 1) {
        Row(
            Modifier.padding(start = edge.start, end = edge.end).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            seasons.forEach { n -> TabChip(tr(R.string.detail_season, n), vm.season == n) { vm.selectSeason(n) } }
        }
        Spacer(Modifier.height(4.dp))
    }
    Column(Modifier.padding(start = edge.start, end = edge.end)) {
        if (vm.epLoading) Text(tr(R.string.detail_loading_eps), color = Accent)
        vm.error?.let { Text(tr(R.string.detail_error, it), color = Danger, fontSize = 13.sp) }
        if (!vm.epLoading && vm.episodes.isEmpty() && vm.error == null) Text(tr(R.string.detail_no_eps), color = TextSecondary)
    }

    val eps = vm.episodes.filter { it.season == vm.season }
    // Land where the user was (returning from playback); first time: the first episode not yet finished.
    val remembered = vm.epFocusUrl?.let { u -> eps.indexOfFirst { it.url == u } }?.takeIf { it >= 0 }
    val target = remembered ?: eps.indexOfFirst { e -> vm.store.historyOf(e.url).let { it == null || !it.finished } }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    val epFocus = remember { FocusRequester() }
    LaunchedEffect(eps.size, vm.season) {
        if (eps.isNotEmpty()) {
            runCatching { listState.scrollToItem(target) }
            if (d.tv && remembered != null) {
                kotlinx.coroutines.delay(200)
                runCatching { epFocus.requestFocus() }
            }
        }
    }
    val cardW = when { d.tv -> 250.dp; d.widthDp >= 600 -> 230.dp; else -> 184.dp }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(if (d.tv) 18.dp else 12.dp),
        contentPadding = PaddingValues(start = edge.start, end = edge.end, top = 12.dp, bottom = 8.dp)
    ) {
        itemsIndexed(eps, key = { _, e -> e.url }) { idx, e ->
            EpisodeCard(
                e = e, h = vm.historyByUrl[e.url], fallback = vm.info?.backdrop ?: s.logo, d = d, width = cardW,
                focusRequester = if (idx == target) epFocus else null,
                onFocused = { vm.epFocusUrl = e.url },
                onClick = { vm.epFocusUrl = e.url; vm.playEpisode(s, e) }
            )
        }
    }
    val selEp = eps.firstOrNull { it.url == vm.epFocusUrl } ?: eps.getOrNull(target)
    if (selEp != null && DownloadRules.isDownloadable(selEp.url)) {
        Row(Modifier.padding(start = edge.start, end = edge.end, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            DownloadButton(
                vm, "${s.name} · ${tr(R.string.ep_label, selEp.season, selEp.num)}", selEp.url, s.logo, Accent,
                label = tr(R.string.dl_ep, selEp.season, selEp.num)
            )
            DownloadSeasonButton(vm, s, eps)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeCard(
    e: Episode,
    h: HistoryEntry?,
    fallback: String?,
    d: Device,
    width: Dp,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (focused) 1.07f else 1f, tween(170), label = "epZoom")
    val real = !e.image.isNullOrBlank()
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.width(width)
            .scale(sc)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick)   // no default focus overlay: the white ring on the picture is the only focus mark
    ) {
        // Clean 16:9 still: no number, no icon, no overlay — only a focus ring + glow when selected.
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .softShadow(shape, 6.dp, Color.Black)   // focus is the white ring only: no white glow spilling over the title and the icons
                .clip(shape).background(SurfHigh)
                .border(if (focused) 2.5.dp else 0.dp, Color.White, shape)
        ) {
            AsyncImage(
                model = if (real) e.image else fallback, contentDescription = null,
                contentScale = ContentScale.Crop, alpha = if (real) 1f else 0.45f,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Everything textual lives BELOW the image.
        if (h != null && h.progress > 0f && h.finished.not()) {
            Box(Modifier.padding(top = 7.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Ink.copy(alpha = 0.16f))) {
                Box(Modifier.fillMaxWidth(h.progress.coerceIn(0f, 1f)).fillMaxHeight().background(Accent))
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(R.string.ep_number, e.num), fontSize = if (d.tv) 13.sp else 12.sp, color = Accent, fontWeight = FontWeight.Bold, maxLines = 1)
            if (h?.finished == true) Text(tr(R.string.ep_watched), fontSize = 11.sp, color = TextMuted, maxLines = 1)
        }
        if (e.title.isNotBlank()) {
            Text(
                e.title, fontSize = if (d.tv) 14.sp else 13.sp, color = Ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold
            )
        }
        if (e.plot.isNotBlank() && d.heightDp >= 500) {
            Text(e.plot, fontSize = 11.5.sp, lineHeight = 15.sp, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The other copies of this title: the one on screen (ticked) and the rest. A movie starts playing; a series opens its own page. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VersionChips(vm: AppViewModel, c: Channel, versions: List<Channel>) {
    val all = listOf(c) + versions
    val labels = all.mapIndexed { i, v ->
        val g = v.group.trim()
        if (g.isNotEmpty() && all.count { it.group.trim() == g } == 1) g else tr(R.string.version_n, i + 1)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr(R.string.versions_label), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0x99FFFFFF))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            all.forEachIndexed { i, v ->
                Focusable(selected = i == 0, onClick = { if (i > 0) { if (kindOf(v) == Kind.Movie) vm.play(v) else vm.openDetail(v) } }) {
                    Text(
                        (if (i == 0) "✓ " else "") + labels[i], fontSize = 14.sp, color = Ink, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                    )
                }
            }
        }
    }
}

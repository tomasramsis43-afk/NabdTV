package com.nabd.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailScreen(vm: AppViewModel) {
    val c = vm.detail ?: return
    val isSeries = kindOf(c) == Kind.Series
    val info = vm.info
    BackHandler { vm.closeDetail() }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(c.url) { if (!isSeries) { kotlinx.coroutines.delay(150); runCatching { playFocus.requestFocus() } } }
    val d = LocalDevice.current
    val edge = edgePad(d)
    val narrow = d.widthDp < 600
    val shortScreen = d.heightDp < 440
    val palette = rememberArtworkPalette(info?.backdrop ?: c.logo)
    val typeLabel = tr(if (isSeries) R.string.detail_type_series else R.string.detail_type_movie)

    Box(Modifier.fillMaxSize().appBackground()) {
        // Full-bleed backdrop: wide artwork to the screen edges, dissolving into the page (no frame, no card).
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.72f)) {
            AsyncImage(
                model = info?.backdrop ?: c.logo, contentDescription = null,
                contentScale = ContentScale.Crop, alignment = BiasAlignment(0f, -0.45f), modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.fillMaxSize().textSideScrim(if (narrow) 0.78f else 0.95f))
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.0f to Scrim.copy(alpha = 0.55f), 0.30f to Color.Transparent, 0.62f to Scrim.copy(alpha = 0.62f), 1f to Bg)
                )
            )
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(palette.glow, Color.Transparent),
                            center = Offset(size.width * 0.15f, size.height * 0.05f),
                            radius = size.width * 0.5f
                        ),
                        radius = size.width * 0.5f,
                        center = Offset(size.width * 0.15f, size.height * 0.05f)
                    )
                }
            )
        }

        Column(Modifier.fillMaxSize().padding(top = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = edge.start, end = edge.end), verticalAlignment = Alignment.CenterVertically) {
                MBtn(tr(R.string.detail_back), filled = false) { vm.closeDetail() }
                Spacer(Modifier.weight(1f))
                Text(
                    typeLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(palette.primary.copy(alpha = 0.55f))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }

            Column(
                Modifier.fillMaxWidth().weight(1f).padding(start = edge.start, end = edge.end),
                verticalArrangement = Arrangement.Bottom
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (!narrow && !shortScreen) {
                        val shape = RoundedCornerShape(14.dp)
                        Box(
                            Modifier.width(if (d.tv) 132.dp else 116.dp).aspectRatio(2f / 3f)
                                .softShadow(shape, 16.dp, palette.primary).clip(shape).background(SurfHigh)
                        ) {
                            AsyncImage(model = c.logo, contentDescription = c.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Bottom) {
                        Text(
                            c.name, style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.65f), Offset(0f, 3f), 14f)), fontSize = if (narrow) 18.sp else if (d.tv) 26.sp else 22.sp,
                            lineHeight = if (d.tv) 32.sp else 28.sp,
                            fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            (info?.rating?.takeIf { it.isNotBlank() } ?: ratingBadge(c))?.let {
                                MetaChip("★ $it", fg = Color(0xFFFFD27A), bg = Color(0x33FFB74D))
                            }
                            info?.year?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
                            info?.duration?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
                            info?.genre?.takeIf { it.isNotBlank() }?.split(',', '،', '/')?.map { it.trim() }
                                ?.filter { it.isNotEmpty() }?.take(if (narrow) 1 else 2)?.forEach { MetaChip(it) }
                        }
                        if (info != null && info.plot.isNotBlank() && !(isSeries && shortScreen)) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                info.plot, fontSize = if (narrow) 12.sp else 13.sp, lineHeight = 19.sp,
                                color = Color(0xFFE3E6F0), maxLines = if (narrow || isSeries) 2 else 3, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (info != null && info.cast.isNotBlank() && !shortScreen) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                tr(R.string.detail_cast, info.cast), fontSize = 11.5.sp, color = Color(0xFFC3C8D8),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!isSeries) {
                        val h = vm.store.historyOf(c.url)
                        val label = if (h != null && !h.finished && h.position > 10_000) tr(R.string.detail_resume, h.position / 60000) else tr(R.string.detail_play)
                        MBtn(label, tint = buttonTone(palette.primary), modifier = Modifier.focusRequester(playFocus)) { vm.play(c) }
                    }
                    MBtn(if (c.url in vm.favs) tr(R.string.detail_fav_on) else tr(R.string.detail_fav_off), filled = false, tint = palette.accent) { vm.toggleFav(c) }
                }
                Spacer(Modifier.height(10.dp))
            }

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
            } else {
                Spacer(Modifier.height(24.dp))
            }
        }
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
            if (d.tv) {
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
            .clickable(onClick = onClick)
    ) {
        // Clean 16:9 still: no number, no icon, no overlay — only a focus ring + glow when selected.
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .softShadow(shape, if (focused) 18.dp else 6.dp, if (focused) Color.White else Color.Black)
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

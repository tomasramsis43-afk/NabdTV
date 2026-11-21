package com.nabd.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val narrow = d.widthDp < 600
    val palette = rememberArtworkPalette(info?.backdrop ?: c.logo)
    val typeLabel = tr(if (isSeries) R.string.detail_type_series else R.string.detail_type_movie)

    Box(Modifier.fillMaxSize().appBackground()) {
        // Artwork fills the upper part and dissolves into the page — the title block never sits on busy pixels.
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.62f)) {
            AsyncImage(
                model = info?.backdrop ?: c.logo, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Scrim.copy(alpha = 0.22f), Scrim.copy(alpha = 0.72f), Scrim.copy(alpha = 0.96f)))
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

        Column(Modifier.fillMaxSize().padding(start = d.pad, end = d.pad, top = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MBtn(tr(R.string.detail_back), filled = false) { vm.closeDetail() }
                Spacer(Modifier.weight(1f))
                Text(
                    typeLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(palette.primary.copy(alpha = 0.55f))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }

            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Bottom) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (!narrow) {
                        val shape = RoundedCornerShape(14.dp)
                        Box(
                            Modifier.width(if (d.tv) 132.dp else 116.dp).aspectRatio(2f / 3f)
                                .softShadow(shape, 16.dp, palette.primary).clip(shape)
                                .background(SurfHigh).border(1.dp, Color(0x2EFFFFFF), shape)
                        ) {
                            AsyncImage(model = c.logo, contentDescription = c.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Bottom) {
                        Text(
                            c.name, fontSize = if (narrow) 22.sp else if (d.tv) 32.sp else 27.sp,
                            lineHeight = if (d.tv) 38.sp else 32.sp,
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
                        if (info != null && info.plot.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                info.plot, fontSize = if (narrow) 12.sp else 13.sp, lineHeight = 19.sp,
                                color = Color(0xFFD9DCE8), maxLines = if (narrow) 2 else 3, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (info != null && info.cast.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                tr(R.string.detail_cast, info.cast), fontSize = 11.5.sp, color = Color(0xFFAEB4C6),
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
                Spacer(Modifier.height(12.dp))
            }

            if (isSeries) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(tr(R.string.detail_episodes), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
                    if (vm.episodes.isNotEmpty()) Text(tr(R.string.detail_episode_count, vm.episodes.size), fontSize = 12.sp, color = TextMuted)
                }
                Column(Modifier.fillMaxWidth().weight(1f)) { Episodes(vm, c) }
            } else {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Episodes(vm: AppViewModel, s: Channel) {
    val seasons = vm.episodes.map { it.season }.distinct()
    if (seasons.size > 1) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            seasons.forEach { n -> TabChip(tr(R.string.detail_season, n), vm.season == n) { vm.selectSeason(n) } }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (vm.epLoading) Text(tr(R.string.detail_loading_eps), color = Accent)
    vm.error?.let { Text(tr(R.string.detail_error, it), color = Danger, fontSize = 13.sp) }
    if (!vm.epLoading && vm.episodes.isEmpty() && vm.error == null) Text(tr(R.string.detail_no_eps), color = TextSecondary)

    val eps = vm.episodes.filter { it.season == vm.season }
    val target = eps.indexOfFirst { e -> vm.store.historyOf(e.url).let { it == null || !it.finished } }.coerceAtLeast(0)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val epFocus = remember { FocusRequester() }
    // Remote: land on the episode to watch next (first unfinished) so OK starts it immediately.
    LaunchedEffect(eps.size, vm.season) {
        if (eps.isNotEmpty()) {
            runCatching { listState.scrollToItem(target) }
            kotlinx.coroutines.delay(200)
            runCatching { epFocus.requestFocus() }
        }
    }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(eps, key = { _, e -> e.url }) { idx, e ->
            val h = vm.historyByUrl[e.url]
            Focusable(
                onClick = { vm.playEpisode(s, e) }, modifier = Modifier.fillMaxWidth(),
                focusRequester = if (idx == target) epFocus else null, zoom = 1.02f
            ) {
                EpisodeCard(e, h, vm.info?.backdrop ?: s.logo, LocalDevice.current.tv)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeCard(e: Episode, h: HistoryEntry?, fallback: String?, tv: Boolean) {
    val real = !e.image.isNullOrBlank()
    Row(
        Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.width(if (tv) 230.dp else 150.dp).aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp)).background(SurfHigh)
        ) {
            AsyncImage(
                model = if (real) e.image else fallback, contentDescription = null,
                contentScale = ContentScale.Crop, alpha = if (real) 1f else 0.45f,
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Scrim.copy(alpha = 0.8f)))))
            Text("▶", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
            if (h != null && h.progress > 0f) Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(h.progress).height(3.dp).background(Accent)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(tr(R.string.ep_label, e.season, e.num), fontSize = 11.sp, color = Accent, fontWeight = FontWeight.Bold)
            Text(
                e.title.ifBlank { tr(R.string.ep_default_title, e.num) }, fontSize = 14.sp, color = Ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold
            )
            if (e.plot.isNotBlank()) Text(e.plot, fontSize = 12.sp, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (h?.finished == true) Text(tr(R.string.ep_watched), fontSize = 11.sp, color = Accent)
        }
    }
}

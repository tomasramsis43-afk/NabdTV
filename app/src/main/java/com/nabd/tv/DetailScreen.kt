package com.nabd.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val playFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(c.url) { if (!isSeries) { kotlinx.coroutines.delay(150); runCatching { playFocus.requestFocus() } } }
    val d = LocalDevice.current
    val narrow = d.widthDp < 600
    Box(Modifier.fillMaxSize().appBackground()) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.52f)) {
            AsyncImage(model = info?.backdrop ?: c.logo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0x550E1235), Color(0xF00E1235)))))
        }
        val details: @Composable ColumnScope.() -> Unit = {
            Text(c.name, fontSize = if (narrow) 20.sp else 26.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                info?.year?.takeIf { it.isNotBlank() }, info?.genre?.takeIf { it.isNotBlank() },
                info?.duration?.takeIf { it.isNotBlank() }, info?.rating?.takeIf { it.isNotBlank() }?.let { "★ $it" }
            ).joinToString("  ·  ")
            if (meta.isNotEmpty()) Text(meta, fontSize = 13.sp, color = Accent)
            if (info != null && info.plot.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(info.plot, fontSize = 13.sp, color = Color(0xFFE0E2FF), maxLines = if (narrow) 2 else 3, overflow = TextOverflow.Ellipsis)
            }
            if (info != null && info.cast.isNotBlank()) Text("بطولة: ${info.cast}", fontSize = 12.sp, color = Color(0xFFC9CBFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val actions: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isSeries) {
                    val h = vm.store.historyOf(c.url)
                    MBtn(if (h != null && !h.finished && h.position > 10_000) "▶ استكمال (${h.position / 60000} د)" else "▶ تشغيل", modifier = Modifier.focusRequester(playFocus)) { vm.play(c) }
                }
                MBtn(if (c.url in vm.favs) "★ في المفضلة" else "☆ مفضلة", filled = false) { vm.toggleFav(c) }
                MBtn("رجوع", filled = false) { vm.closeDetail() }
            }
        }
        Column(Modifier.fillMaxSize().padding(if (narrow) d.pad else 24.dp)) {
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Bottom) {
                Column(Modifier.fillMaxWidth(if (narrow) 1f else 0.65f), content = details)
                Spacer(Modifier.height(10.dp))
                actions()
                Spacer(Modifier.height(12.dp))
            }
            Column(Modifier.fillMaxWidth().weight(1f)) {
                if (isSeries) Episodes(vm, c)
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
            seasons.forEach { n -> TabChip("الموسم $n", vm.season == n) { vm.selectSeason(n) } }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (vm.epLoading) Text("جارٍ تحميل الحلقات...", color = Accent)
    vm.error?.let { Text("خطأ: $it", color = Danger, fontSize = 13.sp) }
    if (!vm.epLoading && vm.episodes.isEmpty() && vm.error == null) Text("لا توجد حلقات", color = TextSecondary)
    val eps = vm.episodes.filter { it.season == vm.season }
    val target = eps.indexOfFirst { e -> vm.store.historyOf(e.url).let { it == null || !it.finished } }.coerceAtLeast(0)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val epFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Remote: land on the episode to watch next (first unfinished) so OK starts it immediately.
    LaunchedEffect(eps.size, vm.season) {
        if (eps.isNotEmpty()) {
            runCatching { listState.scrollToItem(target) }
            kotlinx.coroutines.delay(200)
            runCatching { epFocus.requestFocus() }
        }
    }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(eps, key = { _, e -> e.url }) { idx, e ->
            val h = vm.store.historyOf(e.url)
            Focusable(onClick = { vm.playEpisode(s, e) }, modifier = Modifier.fillMaxWidth(), focusRequester = if (idx == target) epFocus else null) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "حلقة ${e.num}" + if (e.title.isNotBlank()) " — ${e.title}" else "",
                            fontSize = 15.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (e.plot.isNotBlank()) Text(e.plot, fontSize = 11.sp, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (h != null) Text(if (h.finished) "✓" else "${(h.progress * 100).toInt()}%", color = Accent, fontSize = 12.sp)
                }
            }
        }
    }
}

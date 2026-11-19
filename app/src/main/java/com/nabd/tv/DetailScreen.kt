package com.nabd.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    Box(Modifier.fillMaxSize().background(Bg)) {
        info?.backdrop?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.22f))
        }
        Row(Modifier.fillMaxSize().padding(24.dp)) {
            Column(Modifier.width(190.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(
                    model = c.logo, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(Surf)
                )
                Spacer(Modifier.height(10.dp))
                MBtn("رجوع", filled = false) { vm.closeDetail() }
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                val meta = listOfNotNull(
                    info?.year?.takeIf { it.isNotBlank() }, info?.genre?.takeIf { it.isNotBlank() },
                    info?.duration?.takeIf { it.isNotBlank() }, info?.rating?.takeIf { it.isNotBlank() }?.let { "★ $it" }
                ).joinToString("  ·  ")
                if (meta.isNotEmpty()) Text(meta, fontSize = 13.sp, color = Accent)
                if (info != null && info.plot.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(info.plot, fontSize = 13.sp, color = TextSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                if (info != null && info.cast.isNotBlank()) Text("بطولة: ${info.cast}", fontSize = 12.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!isSeries) {
                        val h = vm.store.historyOf(c.url)
                        MBtn(if (h != null && !h.finished && h.position > 10_000) "▶ استكمال (${h.position / 60000} د)" else "▶ تشغيل") { vm.play(c) }
                    }
                    MBtn(if (c.url in vm.favs) "★ في المفضلة" else "☆ مفضلة", filled = false) { vm.toggleFav(c) }
                }
                Spacer(Modifier.height(12.dp))
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
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(vm.episodes.filter { it.season == vm.season }, key = { it.url }) { e ->
            val h = vm.store.historyOf(e.url)
            Focusable(onClick = { vm.playEpisode(s, e) }, modifier = Modifier.fillMaxWidth()) {
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

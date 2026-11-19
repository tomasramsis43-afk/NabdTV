package com.nabd.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
fun MainScreen(vm: AppViewModel) {
    // Back from a non-home tab returns to Home instead of leaving the app.
    BackHandler(enabled = vm.tab != Tab.Home || vm.query.isNotBlank()) {
        if (vm.query.isNotBlank()) vm.query = "" else vm.selectTab(Tab.Home)
    }
    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 20.dp, vertical = 10.dp)) {
        TopBar(vm)
        Spacer(Modifier.height(6.dp))
        if (vm.loading || vm.vodLoading) Text("جارٍ التحميل...", color = Accent, fontSize = 13.sp)
        vm.error?.let { Text("خطأ: $it", color = Danger, fontSize = 13.sp) }
        vm.healthStatus?.let { Text("فحص الروابط: $it", color = Accent, fontSize = 13.sp) }
        Box(Modifier.weight(1f)) {
            when {
                vm.query.isNotBlank() && (vm.tab == Tab.Home || vm.tab == Tab.Guide || vm.tab == Tab.Recordings) -> SearchResults(vm)
                vm.tab == Tab.Home -> HomeTab(vm)
                vm.tab == Tab.Guide -> GuideTab(vm)
                vm.tab == Tab.Recordings -> RecordingsTab(vm)
                else -> BrowseTab(vm)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TopBar(vm: AppViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Nabd TV", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Accent)
        Spacer(Modifier.width(4.dp))
        LazyRow(Modifier.weight(1.4f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Tab.entries.filter { it != Tab.Movies && it != Tab.Series || vm.hasXtream }) { t ->
                TabChip(t.label, vm.tab == t) { vm.selectTab(t) }
            }
        }
        OutlinedTextField(
            value = vm.query, onValueChange = { vm.query = it }, singleLine = true,
            modifier = Modifier.weight(1f).widthIn(min = 110.dp),
            placeholder = { androidx.compose.material3.Text("بحث...") }
        )
        MBtn(if (vm.loading || vm.vodLoading) "..." else "تحديث", filled = false) { vm.refresh() }
        if (vm.profileList.size > 1) MBtn("👤 ${vm.profile.name}", filled = false) { vm.openProfiles() }
        MBtn("⚙", filled = false) { vm.openSettings() }
    }
}

/* ───────── Home: hero + shelves ───────── */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeTab(vm: AppViewModel) {
    val shelves = vm.shelves
    if (shelves.isEmpty() && !vm.loading) {
        Text("لا يوجد محتوى بعد. حدّث القائمة أو افتح الإعدادات.", color = TextSecondary, modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        vm.hero?.let { h -> item { Hero(vm, h) } }
        items(shelves, key = { it.title }) { s ->
            Column {
                Text(s.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
                    items(s.items) { c ->
                        if (kindOf(c) == Kind.Live) LiveCard(vm, c, Modifier.width(150.dp))
                        else PosterCard(vm, c, Modifier.width(118.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Hero(vm: AppViewModel, c: Channel) {
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.fillMaxWidth().height(190.dp).clip(shape).background(Surf)) {
        if (kindOf(c) != Kind.Live) AsyncImage(model = c.logo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xEE07090D), Color(0x5507090D)))))
        Column(Modifier.align(Alignment.CenterStart).padding(24.dp).fillMaxWidth(0.55f)) {
            Text(c.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val g = Classifier.genre(c)
            val now = vm.nowNext(c).first
            Text(now?.title ?: g.label, fontSize = 14.sp, color = Accent, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MBtn("▶ تشغيل") { vm.open(c) }
                MBtn(if (c.url in vm.favs) "★ في المفضلة" else "☆ مفضلة", filled = false) { vm.toggleFav(c) }
            }
        }
    }
}

/* ───────── Live / Movies / Series browsing ───────── */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BrowseTab(vm: AppViewModel) {
    val poster = vm.tab != Tab.Live
    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.width(200.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(vm.groups, key = { it }) { g ->
                val sel = vm.group == g
                Focusable(selected = sel, onClick = { vm.group = g }, onLongClick = { vm.groupMenu(g) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        vm.groupLabel(g), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        color = if (sel) Accent else Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
            item {
                if (vm.tab == Tab.Live) {
                    Spacer(Modifier.height(8.dp))
                    MBtn("▦ مشاهدة متعددة (${vm.multi.size})", filled = false, modifier = Modifier.fillMaxWidth()) { vm.openMulti() }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("${vm.visible.size}", color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            if (vm.visible.isEmpty() && !vm.loading && !vm.vodLoading)
                Text("لا توجد نتائج", color = TextSecondary, fontSize = 15.sp)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (poster) 120.dp else 150.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(4.dp)
            ) {
                items(vm.visible, key = { it.url + it.id }) { ch -> if (poster) PosterCard(vm, ch) else LiveCard(vm, ch) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchResults(vm: AppViewModel) {
    val r = vm.searchResults
    Column {
        Text("نتائج البحث: ${r.size}", color = TextSecondary, fontSize = 12.sp)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(130.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(4.dp)
        ) {
            items(r, key = { it.url + it.id }) { c -> if (kindOf(c) == Kind.Live) LiveCard(vm, c) else PosterCard(vm, c) }
        }
    }
}

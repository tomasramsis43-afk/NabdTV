package com.nabd.tv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.foundation.lazy.rememberLazyListState
import coil.compose.AsyncImage

/* ───────── EPG guide: channels on the left, programmes of the selected channel on the right ───────── */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GuideTab(vm: AppViewModel) {
    val channels = vm.guideChannels
    if (channels.isEmpty()) {
        Text(
            if (vm.loading) "جارٍ تحميل الدليل..." else "لا توجد بيانات دليل. تأكد من رابط XMLTV أو حدّث القائمة.",
            color = TextSecondary, modifier = Modifier.padding(16.dp)
        )
        return
    }
    val sel = vm.guideChannel ?: channels.first()
    val d = LocalDevice.current
    if (d.widthDp < 600) {
        // Phones: channel chips on top, programmes below.
        Column(Modifier.fillMaxSize()) {
            androidx.compose.foundation.lazy.LazyRow(
                state = vm.listState("guideChips"),
                horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(channels, key = { it.url + it.id }) { c ->
                    Focusable(selected = c.url == sel.url, onClick = { vm.guideChannel = c }, onLongClick = { vm.channelMenu(c) }) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(c.name, fontSize = 13.sp, color = if (c.url == sel.url) Accent else Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            ProgrammeList(vm, sel, Modifier.weight(1f))
        }
        return
    }
    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.width(d.sideList), state = vm.listState("guideChannels"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(channels, key = { it.url + it.id }) { c ->
                Focusable(selected = c.url == sel.url, onClick = { vm.guideChannel = c }, onLongClick = { vm.channelMenu(c) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(c.name, fontSize = 13.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(vm.nowNext(c).first?.title ?: "", fontSize = 11.sp, color = Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        ProgrammeList(vm, sel, Modifier.weight(1f))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProgrammeList(vm: AppViewModel, c: Channel, modifier: Modifier = Modifier) {
    val list = vm.epg[c.url] ?: emptyList()
    val now = System.currentTimeMillis()
    val state = rememberLazyListState()
    val firstCurrent = list.indexOfFirst { it.stop > now }.coerceAtLeast(0)
    LaunchedEffect(c.url) { runCatching { state.scrollToItem(firstCurrent) } }
    Column(modifier) {
        Text(c.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(6.dp))
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list) { p ->
                val live = now in p.start until p.stop
                val past = p.stop <= now
                Focusable(selected = live, onClick = { programmeMenu(vm, c, p) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${hm(p.start)} - ${hm(p.stop)}", fontSize = 13.sp, color = if (live) Accent else TextSecondary, modifier = Modifier.width(if (LocalDevice.current.widthDp < 600) 92.dp else 110.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, fontSize = 15.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.desc.isNotBlank()) Text(p.desc, fontSize = 11.sp, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (live) Text("● الآن", color = Accent, fontSize = 12.sp)
                        else if (past && c.hasCatchup) Text("↺ إعادة", color = Accent, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun programmeMenu(vm: AppViewModel, c: Channel, p: Prog) {
    val now = System.currentTimeMillis()
    val items = ArrayList<Pair<String, () -> Unit>>()
    when {
        now in p.start until p.stop -> {
            items += "▶ مشاهدة الآن" to { vm.play(c, PlayMode.Live) }
            if (c.hasCatchup) items += "↺ من البداية" to { vm.playCatchup(c, p) }
        }
        p.stop <= now -> {
            if (c.hasCatchup) items += "↺ مشاهدة الإعادة" to { vm.playCatchup(c, p) }
        }
        else -> {
            items += "⏺ تسجيل هذا البرنامج" to { vm.record(c, p) }
            items += "⏺ تسجيل كل الحلقات (تلقائي)" to { vm.autoRecord(c, p) }
            items += "🔔 ذكّرني قبل البدء" to { vm.remind(c, p) }
        }
    }
    if (items.isEmpty()) { vm.say("لا توجد إعادة لهذه القناة"); return }
    vm.menu = MenuRequest(p.title, items)
}

/* ───────── Recordings ───────── */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordingsTab(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.refreshRecordings() }
    val list = vm.recordings.filter { it.status != RecStatus.Cancelled }.sortedByDescending { it.start }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("التسجيلات (${list.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
            MBtn("تحديث", filled = false) { vm.refreshRecordings() }
        }
        if (list.isEmpty()) Text("لا توجد تسجيلات. من الدليل اختر برنامجاً قادماً ← تسجيل.", color = TextSecondary, modifier = Modifier.padding(top = 12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(list, key = { it.id }) { r ->
                val label = when (r.status) {
                    RecStatus.Scheduled -> "مجدول"; RecStatus.Recording -> "● يسجّل"
                    RecStatus.Done -> "جاهز"; RecStatus.Failed -> "فشل"; RecStatus.Cancelled -> ""
                }
                Focusable(
                    onClick = {
                        val items = ArrayList<Pair<String, () -> Unit>>()
                        if (r.status == RecStatus.Done && r.file != null) items += "▶ تشغيل" to { vm.playRecording(r) }
                        if (r.status == RecStatus.Scheduled || r.status == RecStatus.Recording) items += "إلغاء" to { vm.cancelRecording(r) }
                        items += "حذف" to { vm.deleteRecording(r) }
                        vm.menu = MenuRequest(r.title, items)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, fontSize = 15.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${r.channelName} · ${dayHm(r.start)}", fontSize = 12.sp, color = TextSecondary)
                        }
                        Text(label, color = if (r.status == RecStatus.Failed) Danger else Accent, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

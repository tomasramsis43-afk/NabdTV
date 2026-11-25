package com.nabd.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/** One button that shows the state of a download (download / queued / % / play / retry) and acts on tap. */
@Composable
fun DownloadButton(vm: AppViewModel, title: String, url: String, poster: String?, tint: Color, label: String? = null) {
    val ctx = LocalContext.current
    val list by Downloads.items.collectAsState()
    val id = remember(url) { DownloadRules.idFor(url) }
    val d = list.firstOrNull { it.id == id && it.status != DlStatus.Cancelled }
    val text = when {
        d == null -> label ?: tr(R.string.dl_download)
        d.status == DlStatus.Queued -> tr(R.string.dl_queued)
        d.status == DlStatus.Downloading -> tr(R.string.dl_progress, DownloadRules.percent(d.bytes, d.total))
        d.status == DlStatus.Done -> tr(R.string.dl_done)
        else -> tr(R.string.dl_retry)
    }
    MBtn(text, filled = false, tint = tint) {
        when {
            d != null && d.status == DlStatus.Done -> vm.playDownload(d)
            d != null && (d.status == DlStatus.Queued || d.status == DlStatus.Downloading) -> {
                Downloads.cancel(ctx, id); vm.say(vm.tx(R.string.dl_cancelled))
            }
            else -> if (Downloads.enqueue(ctx, title, url, poster)) vm.say(vm.tx(R.string.dl_started))
        }
    }
}

/** Queues every episode of the shown season that is not already downloaded or downloading. */
@Composable
fun DownloadSeasonButton(vm: AppViewModel, s: Channel, eps: List<Episode>) {
    val ctx = LocalContext.current
    val list by Downloads.items.collectAsState()
    val todo = eps.filter { e ->
        DownloadRules.isDownloadable(e.url) && list.none { it.id == DownloadRules.idFor(e.url) && it.status != DlStatus.Failed && it.status != DlStatus.Cancelled }
    }
    if (todo.isEmpty()) return
    val season = todo.first().season
    MBtn(tr(R.string.dl_season, season, todo.size), filled = false, tint = Accent) {
        todo.forEach { e -> Downloads.enqueue(ctx, "${s.name} · ${S(R.string.ep_label, e.season, e.num)}", e.url, s.logo) }
        vm.say(vm.tx(R.string.dl_season_started, todo.size))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DownloadsTab(vm: AppViewModel) {
    val ctx = LocalContext.current
    val all by Downloads.items.collectAsState()
    val list = all.filter { it.status != DlStatus.Cancelled }.sortedByDescending { it.added }
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    Column {
        Text(S(R.string.dl_title, list.size), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
        if (list.isEmpty()) Text(S(R.string.dl_empty), color = TextSecondary, modifier = Modifier.padding(top = 12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(list, key = { it.id }) { d ->
                val first = d.id == list.first().id
                if (first) EntryPoint(vm, "downloads", fr)
                Focusable(
                    focusRequester = if (first) fr else null,
                    onClick = {
                        val menu = ArrayList<Pair<String, () -> Unit>>()
                        if (d.status == DlStatus.Done && d.file != null) menu += S(R.string.dl_play) to { vm.playDownload(d) }
                        if (d.status == DlStatus.Queued || d.status == DlStatus.Downloading) menu += S(R.string.dl_cancel) to { Downloads.cancel(ctx, d.id) }
                        if (d.status == DlStatus.Failed) menu += S(R.string.dl_retry) to { Downloads.retry(ctx, d) }
                        menu += S(R.string.dl_delete) to { Downloads.delete(ctx, d.id) }
                        vm.menu = MenuRequest(d.title, menu)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.title, fontSize = 15.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            val label = when (d.status) {
                                DlStatus.Queued -> S(R.string.dl_queued)
                                DlStatus.Downloading -> "${DownloadRules.percent(d.bytes, d.total)}%"
                                DlStatus.Done -> DownloadRules.sizeText(d.bytes)
                                else -> S(R.string.dl_retry)
                            }
                            Text(label, color = if (d.status == DlStatus.Failed) Danger else Accent, fontSize = 13.sp)
                        }
                        if (d.status == DlStatus.Downloading && d.total > 0) {
                            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(3.dp).background(TextSecondary.copy(alpha = 0.25f))) {
                                Box(Modifier.fillMaxWidth(d.bytes.toFloat() / d.total).fillMaxHeight().background(Accent))
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.nabd.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** Fixtures found in the guide: on air now first, then by start time. A card plays the best channel, or lets you pick one. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MatchesRow(vm: AppViewModel, d: Device, list: List<Matches.Match>, start: Dp, end: Dp, cardW: Dp? = null) {
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(60_000); value = System.currentTimeMillis() } }
    val shown = list.filter { it.stop > now }
    if (shown.isEmpty()) return
    val w = cardW ?: (d.shelfPoster * 2.2f)
    Column {
        Row(Modifier.fillMaxWidth().padding(start = start, end = end, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr(R.string.matches_today), fontSize = if (d.tv) 17.sp else 15.sp, fontWeight = FontWeight.Bold, color = Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Text("${shown.size}", fontSize = 12.sp, color = TextMuted)
        }
        LazyRow(
            state = vm.listState("row:matches"),
            horizontalArrangement = Arrangement.spacedBy(d.gap),
            contentPadding = PaddingValues(start = start, end = end, top = 4.dp, bottom = 4.dp)
        ) {
            items(shown, key = { it.home + "|" + it.away + "|" + it.start }) { m -> MatchCard(vm, m, now, w) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MatchCard(vm: AppViewModel, m: Matches.Match, now: Long, w: Dp) {
    val ctx = LocalContext.current
    val live = m.isLive(now)
    val shape = RoundedCornerShape(Radius.poster)
    val time = remember(m.start) { android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(m.start)) }
    Focusable(
        onClick = { vm.playMatch(m) }, zoom = 1.045f, shape = shape,
        modifier = Modifier.width(w).semantics { contentDescription = m.home + " - " + m.away }
    ) {
        Column(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(SurfHigh).padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) {
                    Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Danger))
                    Spacer(Modifier.width(6.dp))
                    Text(tr(R.string.matches_live_now), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Danger)
                } else Text(time, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Accent)
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(m.home, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text("×", fontSize = 12.sp, color = TextMuted)
                Text(m.away, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
            Text(tr(R.string.matches_channels, m.channels.size), fontSize = 11.sp, color = TextSecondary, maxLines = 1)
        }
    }
}

/** One channel plays straight away; several give a short list, healthiest source first. */
fun AppViewModel.playMatch(m: Matches.Match) {
    val sorted = m.channels.sortedBy { health.score(it.url) }
    if (sorted.size == 1) { open(sorted[0]); return }
    menu = MenuRequest(m.home + " × " + m.away, sorted.map { c -> c.name to { open(c) } })
}

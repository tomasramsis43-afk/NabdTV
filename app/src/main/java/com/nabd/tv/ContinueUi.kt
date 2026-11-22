package com.nabd.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage

/**
 * "Continue watching": landscape cards. A series shows its CURRENT EPISODE (that episode's own image, S/E badge,
 * episode title) — never the series poster — and can be moved to the next / previous episode with the arrows
 * (touch) or the long-press menu (TV remote). Movies show their backdrop (poster as a fallback).
 */
@Composable
fun ContinueRow(vm: AppViewModel, d: Device, items: List<ContinueItem>, start: Dp, end: Dp, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = start, end = end, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr(R.string.shelf_continue), fontSize = if (d.tv) 17.sp else 15.sp, fontWeight = FontWeight.Bold, color = Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Text("${items.size}", fontSize = 12.sp, color = TextMuted)
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(d.gap),
            contentPadding = PaddingValues(start = start, end = end, top = 4.dp, bottom = 6.dp)
        ) {
            items(items, key = { it.key }) { ContinueCard(vm, d, it) }
        }
    }
}

@Composable
internal fun ContinueCard(vm: AppViewModel, d: Device, item: ContinueItem) {
    val shape = RoundedCornerShape(Radius.poster)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val img by produceState(initialValue = item.image, item.key, item.episodeUrl, item.image) {
        value = item.image ?: vm.continueImage(item)
    }
    Focusable(
        onClick = { vm.playContinue(item) }, onLongClick = { vm.continueMenu(item) },
        modifier = Modifier.width(if (item.kind == Kind.Series) d.shelfPoster * 1.2f else d.shelfPoster * 2.6f), shape = shape
    ) {
        // Series: the poster itself (portrait) with the episode badge and progress; movies: landscape backdrop.
        Box(Modifier.fillMaxWidth().aspectRatio(if (item.kind == Kind.Series) 2f / 3f else 16f / 9f).clip(shape).background(SurfHigh)) {
            val model = if (item.kind == Kind.Series) item.poster else img ?: item.poster
            if (model != null) AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Scrim.copy(alpha = 0.75f)))))
            Text("\u25B6", color = Color.White, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
            if (item.badge.isNotEmpty()) Text(
                item.badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Scrim.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
            if (item.progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(item.progress).height(3.dp).background(Accent))
            if (!d.tv && item.seriesUrl != null) {
                StepButton(if (rtl) "\u203A" else "\u2039", Modifier.align(Alignment.CenterStart)) { vm.continueStep(item, -1) }
                StepButton(if (rtl) "\u2039" else "\u203A", Modifier.align(Alignment.CenterEnd)) { vm.continueStep(item, 1) }
            }
        }
        Text(
            item.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp)
        )
        if (item.subtitle.isNotEmpty()) Text(
            item.subtitle, fontSize = 11.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
        )
    }
}

@Composable
private fun StepButton(glyph: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.padding(4.dp).size(34.dp).clip(CircleShape).background(Scrim.copy(alpha = 0.65f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(glyph, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
}

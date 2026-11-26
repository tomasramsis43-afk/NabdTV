package com.nabd.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import java.util.Locale

/** "Today" / "Tomorrow" / "in N days" for a release [days] away. */
@Composable
private fun releaseIn(days: Int): String = when {
    days <= 0 -> tr(R.string.next_ep_today)
    days == 1 -> tr(R.string.next_ep_tomorrow)
    else -> tr(R.string.next_ep_in_days, days)
}

/**
 * One "Coming soon" row (movies or series): cards with a countdown badge; a card opens the detail card (trailer, release date,
 * general info). Phone / tablet also get a chip that switches between "most popular" and "soonest first".
 * [cardW] is set by the TV one-row home (landscape cards sized to the free height).
 */
@Composable
fun UpcomingRow(vm: AppViewModel, d: Device, title: String, list: List<UpcomingItem>, start: Dp, end: Dp, cardW: Dp? = null) {
    var open by remember { mutableStateOf<UpcomingItem?>(null) }
    val wide = cardW != null || !d.portrait
    val w = cardW ?: if (wide) d.shelfPoster * 2.6f else d.shelfPoster
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = start, end = end, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title, fontSize = if (d.tv) 17.sp else 15.sp, fontWeight = FontWeight.Bold, color = Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (d.touch) {
                Focusable(onClick = { vm.upcomingByDate = !vm.upcomingByDate }, zoom = 1.04f, shape = RoundedCornerShape(Radius.pill)) {
                    Text(
                        "⇅ " + tr(if (vm.upcomingByDate) R.string.upcoming_sort_date else R.string.upcoming_sort_popular),
                        fontSize = 12.sp, color = Accent, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Text("${list.size}", fontSize = 12.sp, color = TextMuted)
        }
        LazyRow(
            state = vm.listState("row:$title"),
            horizontalArrangement = Arrangement.spacedBy(d.gap),
            contentPadding = PaddingValues(start = start, end = end, top = 4.dp, bottom = 4.dp)
        ) {
            items(list, key = { (if (it.movie) "m" else "t") + it.id }) { u -> UpcomingCard(u, wide, w) { open = u } }
        }
    }
    open?.let { UpcomingDetailDialog(vm, it) { open = null } }
}

@Composable
private fun UpcomingCard(u: UpcomingItem, wide: Boolean, w: Dp, onClick: () -> Unit) {
    val img = if (wide) (u.backdrop ?: u.poster) else (u.poster ?: u.backdrop)
    // Crop only when the picture has the frame's own orientation (backdrop in a wide frame, poster in a tall one);
    // a poster forced into a 16:9 frame (no backdrop yet) would lose most of its height, so show it whole instead.
    val fits = if (wide) u.backdrop != null else u.poster != null
    // Ask TMDB for a sharper copy than the small size saved with the item (also upgrades items cached by older versions).
    val sharp = remember(img, u.backdrop) { img?.let { Upcoming.sharper(it, if (it == u.backdrop) "w780" else "w500") } }
    val shape = RoundedCornerShape(Radius.poster)
    val days = remember(u.date) { Upcoming.daysUntil(u.date, System.currentTimeMillis()) }
    val code = Lang.code
    val dateText = remember(u.date, code) { Upcoming.dateLabel(u.date, Upcoming.locale(code)) }
    val kind = tr(if (u.movie) R.string.upcoming_kind_movie else R.string.upcoming_kind_series)
    val meta = buildString {
        append(kind)
        u.rating?.let { append(" · ★ ").append("%.1f".format(Locale.US, it)) }
        if (wide) dateText?.let { append(" · ").append(it) } else if (u.date.length >= 4) append(" · ").append(u.date.take(4))
    }
    Focusable(onClick = onClick, modifier = Modifier.width(w).semantics { contentDescription = u.title }, zoom = 1.045f, shape = shape) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f).clip(shape).background(SurfHigh)) {
            AsyncImage(model = sharp, contentDescription = null, contentScale = if (fits) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.fillMaxSize())
            if (days != null) {
                Text(
                    releaseIn(days), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color(0xCC05060A)).padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
            // Title and key facts sit on the picture itself, over a dark fade so they stay readable on any image.
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEB05060A))))
                    .padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 6.dp)
            ) {
                Text(
                    u.title, fontSize = if (wide) 13.sp else 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(meta, fontSize = if (wide) 11.sp else 9.sp, color = Color(0xFFD5DAF0), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Full-screen card: picture with title/kind/rating/date on it, trailer button, countdown, genres, summary, cast. Back closes it.
 * On TV the picture sits beside the text, whole and at its own 16:9 shape, so nothing has to scroll the picture out of view.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpcomingDetailDialog(vm: AppViewModel, u: UpcomingItem, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val code = Lang.code
    var det by remember(u.id) { mutableStateOf<UpcomingDetail?>(null) }
    LaunchedEffect(u.id, code) { det = Upcoming.detail(vm.store.tmdbKey, u, code) }
    val loc = remember(code) { Upcoming.locale(code) }
    val fullDate = remember(u.date, code) { Upcoming.dateLabel(u.date, loc) }
    val tv = remember(ctx) { isTelevision(ctx) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            if (tv) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    Box(Modifier.weight(0.46f).align(Alignment.CenterVertically)) {
                        UpcomingHero(u, fullDate, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(Radius.poster)))
                    }
                    Column(Modifier.weight(0.54f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        UpcomingInfo(vm, u, det, code, onClose)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    UpcomingHero(u, fullDate, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    Column(Modifier.padding(16.dp)) {
                        UpcomingInfo(vm, u, det, code, onClose)
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

/** The big picture of the detail card, with the title and key facts drawn on it. */
@Composable
private fun UpcomingHero(u: UpcomingItem, fullDate: String?, modifier: Modifier) {
    val kind = tr(if (u.movie) R.string.upcoming_kind_movie else R.string.upcoming_kind_series)
    Box(modifier.background(SurfHigh)) {
        val hero = remember(u.backdrop, u.poster) {
            (u.backdrop ?: u.poster)?.let { Upcoming.sharper(it, if (u.backdrop != null) "w1280" else "w780") }
        }
        AsyncImage(
            model = hero, contentDescription = null,
            contentScale = if (u.backdrop != null) ContentScale.Crop else ContentScale.Fit,   // never crop a poster into 16:9
            modifier = Modifier.fillMaxSize()
        )
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF005060A))))
                .padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 12.dp)
        ) {
            Text(u.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val line = buildString {
                append(kind)
                u.rating?.let { append(" · ★ ").append("%.1f".format(Locale.US, it)) }
                fullDate?.let { append(" · ").append(it) }
            }
            Text(line, fontSize = 13.sp, color = Color(0xFFD5DAF0), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Everything under/beside the picture: countdown, runtime, genres, trailer + close buttons, tagline, summary, cast. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpcomingInfo(vm: AppViewModel, u: UpcomingItem, det: UpcomingDetail?, code: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val days = remember(u.date) { Upcoming.daysUntil(u.date, System.currentTimeMillis()) }
    val overview = det?.overview?.takeIf { it.isNotBlank() } ?: u.overview
    val trailerId = det?.trailerId.orEmpty()
    Column {
        // kind, rating and release date are already on the picture, so they are not repeated here
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            days?.let { MetaChip("⏰ " + releaseIn(it), fg = Color(0xFFFFD27A), bg = Color(0x33FFB74D)) }
            det?.runtimeMin?.let { MetaChip(tr(R.string.upcoming_runtime, it)) }
            det?.genres?.forEach { MetaChip(it) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MBtn(if (trailerId.isNotBlank()) tr(R.string.detail_trailer) else tr(R.string.detail_trailer_search)) {
                val url = if (trailerId.isNotBlank()) "https://www.youtube.com/watch?v=" + android.net.Uri.encode(trailerId)
                else "https://www.youtube.com/results?search_query=" + android.net.Uri.encode(u.title + " trailer")
                try {
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { vm.say(S(R.string.detail_no_viewer)) }
            }
            MBtn(tr(R.string.upcoming_close), filled = false, onClick = onClose)
        }
        det?.tagline?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, fontSize = 13.sp, color = TextMuted)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            overview.ifBlank { tr(R.string.upcoming_no_overview) },
            fontSize = 14.sp, color = if (overview.isBlank()) TextMuted else Ink
        )
        det?.cast?.takeIf { it.isNotEmpty() }?.let { cast ->
            Spacer(Modifier.height(12.dp))
            Text(tr(R.string.upcoming_cast, cast.joinToString(if (code == "ar") "، " else ", ")), fontSize = 13.sp, color = TextMuted)
        }
    }
}

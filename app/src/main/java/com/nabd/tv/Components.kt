package com.nabd.tv

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
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

fun hm(t: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))

fun dayHm(t: Long): String =
    java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale("ar")).format(java.util.Date(t))

/** Button that works with both touch and D-pad. */
@Composable
fun MBtn(text: String, filled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val pad = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
    val m = modifier.heightIn(min = 40.dp)
    if (filled) Button(onClick = onClick, modifier = m, shape = shape, contentPadding = pad) { androidx.compose.material3.Text(text, maxLines = 1) }
    else OutlinedButton(
        onClick = onClick, modifier = m, shape = shape, contentPadding = pad,
        border = BorderStroke(1.dp, Color(0xFF263241))
    ) { androidx.compose.material3.Text(text, maxLines = 1, color = Color.White) }
}

/** Search box with the same height, radius and border style as the tabs and buttons next to it. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    // Compact by default; grows only while you type.
    val w by animateDpAsState(if (focused || value.isNotEmpty()) 190.dp else 112.dp, tween(160), label = "searchW")
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.width(w).height(34.dp).clip(shape).background(Surf)
            .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Color(0xFF263241), shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🔍", fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text("بحث...", color = TextSecondary, fontSize = 12.sp, maxLines = 1)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            )
        }
        if (value.isNotEmpty()) {
            Text(
                "✕", color = TextSecondary, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onChange("") }.padding(6.dp)
            )
        }
    }
}

/** Keeps the screen awake while this composable is shown (otherwise it dims after 15–30 s of no touch). */
@Composable
fun KeepScreenOn() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(Unit) {
        var c: android.content.Context = ctx
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        val w = (c as? android.app.Activity)?.window
        w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/** Glass tile: translucent fill, soft border, smooth scale on focus. Touch + D-pad + long-press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Focusable(
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    zoom: Float = 1.04f,
    content: @Composable ColumnScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (focused) zoom else 1f, tween(160), label = "zoom")
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .scale(sc)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                if (focused) Brush.verticalGradient(listOf(GlassFocus, Color(0x22FFFFFF)))
                else if (selected) Brush.verticalGradient(listOf(Accent.copy(alpha = 0.22f), Accent.copy(alpha = 0.08f)))
                else Brush.verticalGradient(listOf(GlassFill, Color(0x0DFFFFFF)))
            )
            .border(if (focused) 2.dp else 1.dp, if (focused) Accent else if (selected) Accent.copy(alpha = 0.5f) else GlassBorder, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        content = content
    )
}

@Composable
fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Focusable(selected = selected, onClick = onClick, zoom = 1.06f) {
        Text(
            label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Accent else Color.White,
            modifier = Modifier.padding(horizontal = 14.dp).heightIn(min = 40.dp).wrapContentHeight(Alignment.CenterVertically), maxLines = 1
        )
    }
}

@Composable
fun HealthDot(h: Health) {
    val c = when (h) { Health.Ok -> Accent; Health.Slow -> Color(0xFFFFC857); Health.Dead -> Danger; else -> Color.Transparent }
    if (h != Health.Unknown) Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(c))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveCard(vm: AppViewModel, ch: Channel, modifier: Modifier = Modifier) {
    val locked = vm.parental.needsPin(ch)
    Focusable(onClick = { vm.open(ch) }, onLongClick = { vm.channelMenu(ch) }, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (ch.number > 0) "${ch.number}" else "", fontSize = 10.sp, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (ch.hasCatchup) Text("↺", fontSize = 11.sp, color = Accent)
                    if (locked) Text("🔒", fontSize = 10.sp)
                    HealthDot(vm.health.state(ch.url))
                }
            }
            Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                AsyncImage(model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
                Text(
                    (if (ch.url in vm.favs) "★ " else "") + ch.name,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White
                )
            }
            val (now, _) = vm.nowNext(ch)
            Text(now?.title ?: " ", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = Accent)
            if (now != null) {
                val pct = ((System.currentTimeMillis() - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(pct).background(Accent))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PosterCard(vm: AppViewModel, ch: Channel, modifier: Modifier = Modifier) {
    val progress = vm.history.firstOrNull { it.url == ch.url }?.progress ?: 0f
    val locked = vm.parental.needsPin(ch)
    Focusable(onClick = { vm.open(ch) }, onLongClick = { vm.channelMenu(ch) }, modifier = modifier.fillMaxWidth()) {
        Box {
            AsyncImage(
                model = ch.logo, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Surf)
            )
            if (locked) Text("🔒", modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (progress > 0.02f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Accent))
            }
        }
        Text(
            (if (ch.url in vm.favs) "★ " else "") + ch.name,
            maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Color.White,
            modifier = Modifier.padding(8.dp).height(34.dp)
        )
    }
}

/** Overlay used for the PIN pad, the context menu and the text prompt. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GlassDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(Color(0xF0182130), Color(0xF00D121A))))
                .border(1.dp, GlassBorder, RoundedCornerShape(20.dp)).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

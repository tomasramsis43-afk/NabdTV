package com.nabd.tv

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
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

/** Button that works with both touch and D-pad. Same shape and colors on every device; target size adapts. */
@Composable
fun MBtn(text: String, filled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radius.small)
    val pad = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
    val m = modifier.heightIn(min = LocalDevice.current.control)
    if (filled) Button(
        onClick = onClick, modifier = m, shape = shape, contentPadding = pad,
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
    ) { androidx.compose.material3.Text(text, maxLines = 1, fontWeight = FontWeight.SemiBold) }
    else OutlinedButton(
        onClick = onClick, modifier = m, shape = shape, contentPadding = pad,
        border = BorderStroke(1.dp, Hairline),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = GlassFill, contentColor = Ink)
    ) { androidx.compose.material3.Text(text, maxLines = 1, color = Ink) }
}

/** Round glass icon button (header actions). */
@Composable
fun IconBtn(ic: Ic, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Ink) {
    var focused by remember { mutableStateOf(false) }
    val d = LocalDevice.current
    Box(
        modifier.size(d.control).onFocusChanged { focused = it.isFocused }
            .glass(CircleShape, focused = focused)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { NIcon(ic, tint, 22.dp) }
}

/** Glass search pill. On touch devices it can fill the available width; on TV it stays compact until used. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, fillWidth: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val d = LocalDevice.current
    val w by animateDpAsState(if (focused || value.isNotEmpty()) 230.dp else 130.dp, tween(160), label = "searchW")
    val shape = RoundedCornerShape(Radius.pill)
    Row(
        (if (fillWidth) modifier.fillMaxWidth() else modifier.width(w)).height(d.control)
            .glass(shape, focused = focused).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NIcon(Ic.Search, TextSecondary, 18.dp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text("بحث...", color = TextSecondary, fontSize = 13.sp, maxLines = 1)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = Ink, fontSize = 14.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(28.dp).clip(CircleShape).clickable { onChange("") }, contentAlignment = Alignment.Center) {
                NIcon(Ic.Close, TextSecondary, 14.dp)
            }
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

/**
 * Glass tile: translucent fill, white edge, clear accent ring + smooth zoom on focus.
 * Works with touch, D-pad and long-press. [onFocused] / [focusRequester] let screens remember and restore the focused item.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Focusable(
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    zoom: Float = 1.05f,
    onFocused: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (focused) zoom else 1f, tween(160), label = "zoom")
    val shape = RoundedCornerShape(Radius.small)
    Column(
        modifier
            .scale(sc)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused?.invoke() }
            .then(if (focused) Modifier.softShadow(shape, 14.dp) else Modifier)
            .glass(shape, focused = focused, selected = selected)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        content = content
    )
}

@Composable
fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Focusable(selected = selected, onClick = onClick, zoom = 1.06f) {
        Text(
            label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Accent else Ink,
            modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = LocalDevice.current.control)
                .wrapContentHeight(Alignment.CenterVertically),
            maxLines = 1
        )
    }
}

@Composable
fun HealthDot(h: Health) {
    val c = when (h) { Health.Ok -> Ok; Health.Slow -> Warn; Health.Dead -> Danger; else -> Color.Transparent }
    if (h != Health.Unknown) Box(Modifier.size(8.dp).clip(CircleShape).background(c))
}

/** Returns a requester that grabs focus once if this card was the focused one when the user left the list. */
@Composable
fun rememberCardFocus(vm: AppViewModel, key: String): FocusRequester {
    val fr = remember { FocusRequester() }
    if (vm.restoreFocus && vm.lastFocusKey == key) {
        LaunchedEffect(Unit) { runCatching { fr.requestFocus() }; vm.restoreFocus = false }
    }
    return fr
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveCard(vm: AppViewModel, ch: Channel, modifier: Modifier = Modifier) {
    val d = LocalDevice.current
    val locked = vm.parental.needsPin(ch)
    val key = ch.url + ch.id
    val fr = rememberCardFocus(vm, key)
    Focusable(
        onClick = { vm.open(ch) }, onLongClick = { vm.channelMenu(ch) },
        onFocused = { vm.lastFocusKey = key }, focusRequester = fr,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (ch.number > 0) "${ch.number}" else "", fontSize = 10.sp, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (ch.hasCatchup) Text("↺", fontSize = 11.sp, color = Accent)
                    if (locked) Text("🔒", fontSize = 10.sp)
                    HealthDot(vm.health.state(ch.url))
                }
            }
            Box(Modifier.fillMaxWidth().height(if (d.tv) 56.dp else 46.dp), contentAlignment = Alignment.Center) {
                AsyncImage(model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
                Text(
                    (if (ch.url in vm.favs) "★ " else "") + ch.name,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink
                )
            }
            val (now, _) = vm.nowNext(ch)
            Text(now?.title ?: " ", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = Accent)
            if (now != null) {
                val pct = ((System.currentTimeMillis() - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Hairline)) {
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
    val key = ch.url + ch.id
    val fr = rememberCardFocus(vm, key)
    Focusable(
        onClick = { vm.open(ch) }, onLongClick = { vm.channelMenu(ch) },
        onFocused = { vm.lastFocusKey = key }, focusRequester = fr,
        modifier = modifier.fillMaxWidth()
    ) {
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
            maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Ink,
            modifier = Modifier.padding(8.dp).height(34.dp)
        )
    }
}

/** Overlay used for the PIN pad, the context menu and the text prompt. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GlassDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.softShadow(shape, 24.dp).clip(shape)
                .background(Brush.verticalGradient(if (Ui.dark) listOf(Color(0xF01B2060), Color(0xF00E1235)) else listOf(Color(0xFAFFFFFF), Color(0xFAF1F2FD))))
                .border(1.dp, GlassBorder, shape).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

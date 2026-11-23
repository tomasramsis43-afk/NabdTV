package com.nabd.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private val SplashBg = Color(0xFF0B0F14)
private val SplashMark = Color(0xFF00E5A0)      // the same green as the launcher icon

/**
 * Start-up screen: the logo animates in (spring-like scale + fade) over a soft pulsing glow, a ring turns around it
 * while the cached lists load, then the app fades into the home screen. It waits for real data, but never for
 * less than ~1.5 s (so the animation is seen, not flashed) nor for more than 9 s (so a slow server cannot trap you here).
 */
@Composable
fun SplashScreen(vm: AppViewModel) {
    val d = LocalDevice.current
    LaunchedEffect(Unit) {
        var t0 = System.currentTimeMillis()
        var okSince = 0L
        while (true) {
            delay(100)
            val now = System.currentTimeMillis()
            if (vm.error != null && vm.all.isEmpty()) { okSince = 0L; t0 = now; continue }   // failed: stay here, with Retry / Settings
            if (vm.startupReady()) { if (okSince == 0L) okSince = now } else okSince = 0L
            val stable = okSince != 0L && now - okSince >= 400
            if ((stable && now - t0 >= 1500) || now - t0 >= 9000) break
        }
        vm.splashDone = true
    }

    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val inf = rememberInfiniteTransition(label = "splash")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "spin")
    val glow by inf.animateFloat(0.25f, 0.65f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    val dots by inf.animateFloat(0f, 3.99f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "dots")

    val logo = if (d.tv) 112.dp else 84.dp
    val ring = logo + 40.dp
    val failed = vm.error != null && vm.all.isEmpty()
    val target = when {
        failed -> 0f
        vm.loading || vm.all.isEmpty() -> 0.25f
        vm.vodLoading -> 0.7f
        else -> 1f
    }
    val prog by androidx.compose.animation.core.animateFloatAsState(target, tween(700, easing = FastOutSlowInEasing), label = "prog")
    val status = when {
        failed -> ""
        vm.loading || vm.all.isEmpty() -> tr(R.string.splash_status_live)
        vm.vodLoading -> tr(R.string.splash_status_vod)
        else -> tr(R.string.splash_status_ready)
    }
    // A dim wall of posters from the lists already on the device (empty on the very first launch).
    val wall = remember(vm.movies.size, vm.series.size) {
        (vm.movies.asSequence().take(120) + vm.series.asSequence().take(120)).mapNotNull { it.logo?.takeIf { u -> u.startsWith("http") } }.distinct().take(18).toList()
    }

    Box(Modifier.fillMaxSize().background(SplashBg), contentAlignment = Alignment.Center) {
        if (wall.size >= 6) {
            val cols = if (d.tv) 6 else 3
            Column(Modifier.fillMaxSize().graphicsLayer { alpha = 0.34f * enter.value }) {
                wall.chunked(cols).take(if (d.tv) 3 else 6).forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        row.forEach { u ->
                            coil.compose.AsyncImage(
                                model = u, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.weight(1f).fillMaxSize().padding(3.dp).clip(RoundedCornerShape(8.dp))
                            )
                        }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(SplashBg.copy(alpha = 0.92f), SplashBg.copy(alpha = 0.7f)))))
        Box(
            Modifier.size(ring * 2.2f).graphicsLayer { alpha = glow * enter.value }
                .background(Brush.radialGradient(listOf(SplashMark.copy(alpha = 0.26f), Color.Transparent)), CircleShape)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(ring).graphicsLayer { alpha = enter.value }) {
                    val w = 4.dp.toPx()
                    val sz = Size(size.width - w, size.height - w)
                    val tl = androidx.compose.ui.geometry.Offset(w / 2, w / 2)
                    drawArc(SplashMark.copy(alpha = 0.16f), 0f, 360f, false, tl, sz, style = Stroke(w))
                    drawArc(SplashMark, -90f, 360f * prog.coerceAtLeast(0.02f), false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                }
                Image(
                    painterResource(R.drawable.ic_launcher), contentDescription = null,
                    modifier = Modifier.size(logo).clip(RoundedCornerShape(24.dp))
                        .graphicsLayer { val sc = 0.6f + 0.4f * enter.value; scaleX = sc; scaleY = sc; alpha = enter.value }
                )
            }
            Spacer(Modifier.height(18.dp))
            if (!failed) {
                Text("${(prog * 100).toInt()}%", color = Color.White, fontSize = if (d.tv) 34.sp else 30.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(6.dp))
                Text(status + ".".repeat(dots.toInt()), color = Color(0xFFB3BAD0), fontSize = if (d.tv) 16.sp else 14.sp)
            } else {
                Text(tr(R.string.splash_failed_title), color = Color.White, fontSize = if (d.tv) 24.sp else 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(tr(R.string.splash_failed_hint), color = Color(0xFFB3BAD0), fontSize = 14.sp, modifier = Modifier.widthIn(max = 300.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                val retryFocus = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(Unit) { delay(300); runCatching { retryFocus.requestFocus() } }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SplashBtn(tr(R.string.splash_retry), primary = true, focus = retryFocus) { vm.load(true) }
                    SplashBtn(tr(R.string.splash_settings), primary = false) { vm.splashDone = true; vm.openSettings() }
                }
            }
        }
    }
}

@Composable
private fun SplashBtn(label: String, primary: Boolean, focus: androidx.compose.ui.focus.FocusRequester? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) Color.White else if (primary) SplashMark else Color(0x22FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (focused || primary) Color(0xFF0B0F14) else Color.White)
    }
}

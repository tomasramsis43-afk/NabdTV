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
        val t0 = System.currentTimeMillis()
        var okSince = 0L
        while (true) {
            delay(100)
            val now = System.currentTimeMillis()
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

    val logo = if (d.tv) 120.dp else 92.dp
    val ring = logo + 36.dp
    Box(Modifier.fillMaxSize().background(SplashBg), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(ring * 2.2f).graphicsLayer { alpha = glow * enter.value }
                .background(Brush.radialGradient(listOf(SplashMark.copy(alpha = 0.30f), Color.Transparent)), CircleShape)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(ring).graphicsLayer { alpha = enter.value }) {
                    val w = 3.dp.toPx()
                    val s = Size(size.width - w, size.height - w)
                    val tl = androidx.compose.ui.geometry.Offset(w / 2, w / 2)
                    drawArc(SplashMark.copy(alpha = 0.14f), 0f, 360f, false, tl, s, style = Stroke(w))
                    drawArc(SplashMark, spin, 110f, false, tl, s, style = Stroke(w, cap = StrokeCap.Round))
                }
                Image(
                    painterResource(R.drawable.ic_launcher), contentDescription = null,
                    modifier = Modifier.size(logo).clip(RoundedCornerShape(26.dp))
                        .graphicsLayer { val sc = 0.6f + 0.4f * enter.value; scaleX = sc; scaleY = sc; alpha = enter.value }
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                tr(R.string.app_name), color = Color.White, fontSize = if (d.tv) 30.sp else 24.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 16f }
            )
            Spacer(Modifier.height(10.dp))
            Text(
                tr(R.string.splash_loading) + ".".repeat(dots.toInt()), color = Color(0xFF98A0B3), fontSize = if (d.tv) 15.sp else 13.sp,
                modifier = Modifier.graphicsLayer { alpha = enter.value }
            )
        }
    }
}

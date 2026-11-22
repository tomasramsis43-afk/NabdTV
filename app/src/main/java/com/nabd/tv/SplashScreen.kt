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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private val SplashBg = Color(0xFF0B0F14)
private val SplashMark = Color(0xFF00E5A0)      // the same green as the launcher icon
private val SplashDim = Color(0xFF98A0B3)
private val SplashOff = Color(0xFF3A4252)

/**
 * Start-up screen. A heartbeat line is drawn over the logo ("Nabd" = pulse) while the real loading stages tick off
 * (channels, movies, series, home). It ends when the data is ready (rules in [SplashLogic]) or when the user presses
 * "Enter now". Offline it says so and enters the saved copy; with nothing saved and an error it offers "Try again".
 * With reduced motion (the user's choice, else the system's) everything is static.
 */
@Composable
fun SplashScreen(vm: AppViewModel) {
    val d = LocalDevice.current
    val reduce = vm.reduceMotion
    var offline by remember { mutableStateOf(!vm.isOnline()) }
    val ready = vm.startupReady()
    val failed = vm.error != null && vm.all.isEmpty()
    val stages = vm.splashStages()

    LaunchedEffect(Unit) {
        val t0 = System.currentTimeMillis()
        var okSince = 0L
        while (true) {
            delay(100)
            val now = System.currentTimeMillis()
            val ok = vm.startupReady() && !(vm.error != null && vm.all.isEmpty())    // an error with nothing saved never auto-enters
            if (ok) { if (okSince == 0L) okSince = now } else okSince = 0L
            val min = if (offline) SplashLogic.OFFLINE_MIN_MS else SplashLogic.MIN_MS
            if (SplashLogic.canEnter(now - t0, if (okSince == 0L) 0L else now - okSince, min)) break
        }
        vm.splashDone = true
    }

    val enter = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(reduce) { if (reduce) enter.snapTo(1f) else enter.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val inf = rememberInfiniteTransition(label = "splash")
    val spinA by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "spin")
    val glowA by inf.animateFloat(0.25f, 0.65f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    val pulseA by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing)), label = "pulse")
    val dotsA by inf.animateFloat(0f, 3.99f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "dots")

    val enterFocus = remember { FocusRequester() }
    LaunchedEffect(ready, failed) {
        if (ready && !failed) {
            try { enterFocus.requestFocus() } catch (e: IllegalStateException) { android.util.Log.w("Splash", "enter button not attached yet", e) }
        }
    }

    val logo = if (d.tv) 120.dp else 92.dp
    val ring = logo + 36.dp
    Box(Modifier.fillMaxSize().background(SplashBg), contentAlignment = Alignment.Center) {
        if (!reduce) Box(
            Modifier.size(ring * 2.2f).graphicsLayer { alpha = glowA * enter.value }
                .background(Brush.radialGradient(listOf(SplashMark.copy(alpha = 0.30f), Color.Transparent)), CircleShape)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(ring).graphicsLayer { alpha = enter.value }) {
                    val w = 3.dp.toPx()
                    val s = Size(size.width - w, size.height - w)
                    val tl = Offset(w / 2, w / 2)
                    drawArc(SplashMark.copy(alpha = 0.14f), 0f, 360f, false, tl, s, style = Stroke(w))
                    if (!reduce) drawArc(SplashMark, spinA, 110f, false, tl, s, style = Stroke(w, cap = StrokeCap.Round))
                }
                Image(
                    painterResource(R.drawable.ic_launcher), contentDescription = null,
                    modifier = Modifier.size(logo).clip(RoundedCornerShape(26.dp))
                        .graphicsLayer { val sc = 0.6f + 0.4f * enter.value; scaleX = sc; scaleY = sc; alpha = enter.value }
                )
            }
            Spacer(Modifier.height(10.dp))
            Heartbeat(pulseA, reduce, Modifier.size(width = 130.dp, height = 36.dp))
            Text(
                tr(R.string.app_name), color = Color.White, fontSize = if (d.tv) 30.sp else 24.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.graphicsLayer { alpha = enter.value }
            )
            Spacer(Modifier.height(12.dp))
            Column(Modifier.width(210.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                val active = stages.indexOfFirst { !it.second }
                stages.forEachIndexed { i, (stage, done) -> StageRow(stageLabel(stage), done, i == active) }
            }
            Spacer(Modifier.height(10.dp))
            val note = when {
                failed -> vm.error.orEmpty()
                offline -> tr(R.string.splash_offline)
                ready -> tr(R.string.splash_ready)
                else -> tr(R.string.splash_loading) + if (reduce) "…" else ".".repeat(dotsA.toInt())
            }
            Text(note, color = SplashDim, fontSize = if (d.tv) 15.sp else 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp).graphicsLayer { alpha = enter.value })
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp)) {
                if (ready && !failed) MBtn(tr(R.string.splash_enter_now), modifier = Modifier.focusRequester(enterFocus)) { vm.splashDone = true }
                if (offline || failed) MBtn(tr(R.string.splash_retry), filled = false) {
                    offline = !vm.isOnline()
                    vm.retryStartup()
                }
            }
        }
    }
}

@Composable
private fun stageLabel(s: SplashLogic.Stage): String = tr(
    when (s) {
        SplashLogic.Stage.Channels -> R.string.stage_channels
        SplashLogic.Stage.Movies -> R.string.stage_movies
        SplashLogic.Stage.Series -> R.string.stage_series
        SplashLogic.Stage.Home -> R.string.stage_home
    }
)

@Composable
private fun StageRow(label: String, done: Boolean, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (done) "✓" else "•", color = if (done) SplashMark else if (active) SplashDim else SplashOff, fontSize = 14.sp, modifier = Modifier.width(16.dp), textAlign = TextAlign.Center)
        Text(label, color = if (done) Color.White else SplashDim, fontSize = 13.sp)
    }
}

/** ECG-style line that draws itself over and over (fully drawn and still when [still]). */
@Composable
private fun Heartbeat(progress: Float, still: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val p = Path().apply {
            moveTo(0f, h * .5f); lineTo(w * .29f, h * .5f); lineTo(w * .37f, h * .5f); lineTo(w * .43f, h * .13f)
            lineTo(w * .51f, h * .87f); lineTo(w * .58f, h * .30f); lineTo(w * .64f, h * .5f); lineTo(w, h * .5f)
        }
        val m = PathMeasure(); m.setPath(p, false)
        val len = m.length
        val prog = if (still) 1f else progress
        drawPath(
            p, SplashMark,
            style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(len, len), len * (1f - prog)))
        )
    }
}

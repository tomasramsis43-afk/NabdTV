package com.nabd.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Nabd TV design system v3 — a calm, cinematic dark surface built for 10-foot viewing.
 *
 * Rules kept across every screen:
 *  • near-black canvas, neutral graphite surfaces; no blue wash, no glowing boxes
 *  • one solid primary accent, one warm secondary accent (ratings / highlights)
 *  • depth from gradients, shadows and rounded artwork — not from borders
 *  • artwork-driven colours are injected only where a title is presented (see DynamicPalette.kt)
 *  • spacing scale: 4 / 8 / 12 / 16 / 24 / 32
 */
object Ui {
    var dark by mutableStateOf(true)
    /** Index into [AccentPalettes]; the user's colour choice. */
    var accent by mutableIntStateOf(0)
    /** True black canvas on dark mode (saves power on OLED panels, deepest contrast). */
    var black by mutableStateOf(false)
}

/** Accent colour choices: text/outline tone on dark, solid fill on dark (white text stays readable), and the single tone used on light. */
class AccentPalette(val dark: Color, val fillDark: Color, val light: Color)
val AccentPalettes = listOf(
    AccentPalette(Color(0xFF8A93FF), Color(0xFF5C63E8), Color(0xFF4A55D6)),   // indigo (default)
    AccentPalette(Color(0xFF4FD1C5), Color(0xFF1F8F86), Color(0xFF0F766E)),   // teal
    AccentPalette(Color(0xFFFF8FB1), Color(0xFFD63E75), Color(0xFFC2255C)),   // rose
    AccentPalette(Color(0xFFFFC857), Color(0xFFB4690C), Color(0xFF9A6B12)),   // amber
    AccentPalette(Color(0xFF7EE081), Color(0xFF2F8F42), Color(0xFF2B8A3E)),   // green
    AccentPalette(Color(0xFFFF9F68), Color(0xFFD9480F), Color(0xFFD9480F))    // orange
)
private val pal: AccentPalette get() = AccentPalettes[Ui.accent.coerceIn(0, AccentPalettes.lastIndex)]

/* ── core palette ─────────────────────────────────────────────────────────── */

val Accent: Color get() = if (Ui.dark) pal.dark else pal.light
/** Solid variant for filled buttons/chips: white text keeps AA contrast on it. */
val AccentFill: Color get() = if (Ui.dark) pal.fillDark else pal.light
val Accent2: Color get() = if (Ui.dark) Color(0xFFE5B567) else Color(0xFF9A6B12)
val OnAccent: Color get() = Color.White

val Bg: Color get() = if (Ui.dark) (if (Ui.black) Color.Black else Color(0xFF08090D)) else Color(0xFFF5F6FA)
val Surf: Color get() = if (Ui.dark) (if (Ui.black) Color(0xFF0C0C0F) else Color(0xFF14161D)) else Color.White
val SurfHigh: Color get() = if (Ui.dark) (if (Ui.black) Color(0xFF151519) else Color(0xFF1B1E27)) else Color(0xFFEDEFF5)
val Ink: Color get() = if (Ui.dark) Color(0xFFF3F5FA) else Color(0xFF14161D)
val TextSecondary: Color get() = if (Ui.dark) Color(0xFF98A0B3) else Color(0xFF646B7C)
val TextMuted: Color get() = if (Ui.dark) Color(0xFF6B7385) else Color(0xFF8B92A4)

/** Text on top of video/overlays: always light, independent of the app theme. */
val OverlayText = Color(0xFFB8C2CF)
val OverlayTextMuted = Color(0xFF9AA3B8)
/** Hairline border of panels drawn over video. */
val OverlayLine = Color(0x44FFFFFF)
/** Dark scrims behind panels / labels drawn over video (medium and strong). */
val OverlayScrim = Color(0x99000000)
val OverlayScrimStrong = Color(0xCC000000)

val Danger: Color get() = if (Ui.dark) Color(0xFFFF7B7B) else Color(0xFFD93B3B)
val Ok: Color get() = if (Ui.dark) Color(0xFF4BD6A2) else Color(0xFF12855D)
val Warn: Color get() = if (Ui.dark) Color(0xFFFFC65C) else Color(0xFF9A6B12)

val GlassFill: Color get() = if (Ui.dark) Color(0x0DFFFFFF) else Color(0xCCFFFFFF)
val GlassBorder: Color get() = if (Ui.dark) Color(0x1FFFFFFF) else Color(0xFFE3E6EF)
val Hairline: Color get() = if (Ui.dark) Color(0x14FFFFFF) else Color(0xFFE6E8F0)
val Navy: Color get() = if (Ui.dark) Color(0xFF101218) else Color(0xFF22252F)
val NavyDeep: Color get() = if (Ui.dark) Color(0xFF0A0B10) else Color(0xFF15171E)

/** Neutral scrim used over artwork so text always stays readable. */
val Scrim: Color get() = if (Ui.dark) Color(0xFF05060A) else Color(0xFF0B0D12)

object Radius {
    val card = 20.dp
    val small = 14.dp
    val pill = 40.dp
    val poster = 12.dp
    val hero = 26.dp
}

/* ── surfaces ─────────────────────────────────────────────────────────────── */

/** Page background: deep neutral canvas with one very soft accent glow in the corner. */
fun Modifier.appBackground(): Modifier = this.drawBehind {
    drawRect(
        Brush.linearGradient(
            if (Ui.dark) listOf(Color(0xFF07080C), Color(0xFF0B0D13), Color(0xFF07080C))
            else listOf(Color(0xFFFAFAFD), Color(0xFFF1F2F8), Color(0xFFF7F7FC)),
            start = Offset.Zero, end = Offset(size.width, size.height)
        )
    )
    drawCircle(
        Brush.radialGradient(
            listOf(Accent.copy(alpha = if (Ui.dark) 0.10f else 0.10f), Color.Transparent),
            center = Offset(size.width * 0.88f, -size.height * 0.05f), radius = size.minDimension * 1.05f
        ),
        radius = size.minDimension * 1.05f, center = Offset(size.width * 0.88f, -size.height * 0.05f)
    )
}

/** Soft, slightly coloured shadow used to lift focused cards off the canvas. */
fun Modifier.softShadow(shape: Shape, elevation: Dp = 10.dp, tint: Color = Accent): Modifier =
    this.shadow(elevation, shape, clip = false, ambientColor = tint.copy(alpha = 0.10f), spotColor = tint.copy(alpha = 0.28f))

/** Frosted surface: translucent fill + hairline edge. Used for rails, sheets and secondary cards. */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(Radius.card),
    focused: Boolean = false,
    selected: Boolean = false,
    tint: Color = Accent
): Modifier = this
    .clip(shape)
    .background(
        when {
            focused -> Brush.verticalGradient(listOf(tint.copy(alpha = 0.16f), tint.copy(alpha = 0.08f)))
            selected -> Brush.verticalGradient(listOf(tint.copy(alpha = 0.18f), tint.copy(alpha = 0.07f)))
            else -> Brush.verticalGradient(listOf(GlassFill, GlassFill.copy(alpha = GlassFill.alpha * 0.6f)))
        }
    )
    .border(
        if (focused) 2.dp else 1.dp,
        when {
            focused -> tint.copy(alpha = 0.9f)
            selected -> tint.copy(alpha = 0.55f)
            else -> GlassBorder
        },
        shape
    )

/**
 * Section heading. The accent word keeps the brand voice without heavy decoration:
 * same sans family, accent colour, slightly tighter tracking.
 */
@Composable
fun AccentTitle(text: String, accent: String, size: TextUnit = 26.sp, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            append(text)
            if (accent.isNotBlank()) append(" ")
            withStyle(SpanStyle(color = Accent, fontWeight = FontWeight.Bold)) { append(accent) }
        },
        fontSize = size, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 2, modifier = modifier
    )
}


/**
 * Dark wash on the side where text sits (the start side), mirrored automatically in RTL.
 * Used over full-bleed artwork so titles stay readable without a visible box.
 */
@Composable
fun Modifier.textSideScrim(strength: Float = 0.78f): Modifier {
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val c = Scrim.copy(alpha = strength)
    val ltr = listOf(
        0f to c, 0.30f to c.copy(alpha = strength * 0.88f), 0.55f to c.copy(alpha = strength * 0.45f),
        0.80f to c.copy(alpha = strength * 0.12f), 1f to Color.Transparent
    )
    val stops = if (rtl) ltr.map { (1f - it.first) to it.second }.reversed() else ltr
    return this.background(Brush.horizontalGradient(*stops.toTypedArray()))
}

/** Horizontal screen padding that keeps foreground content clear of a camera cut-out while artwork bleeds under it. */
@androidx.compose.runtime.Immutable
data class EdgePad(val start: Dp, val end: Dp)

@Composable
fun edgePad(d: Device): EdgePad {
    val ld = androidx.compose.ui.platform.LocalLayoutDirection.current
    val cut = WindowInsets.displayCutout.asPaddingValues()
    return EdgePad(d.pad + cut.calculateStartPadding(ld), d.pad + cut.calculateEndPadding(ld))
}


/**
 * Soft top edge for a scrolling list: instead of being cut by a hard line, content fades out over [height]
 * (real transparency, so any background shows through). [amount] is 0..1 and is read at draw time:
 * 0 leaves the list untouched (e.g. while it sits at the very top), 1 is the full fade.
 */
fun Modifier.fadeTopEdge(height: Dp = 40.dp, amount: () -> Float = { 1f }): Modifier = this
    .graphicsLayer {
        compositingStrategy = if (amount() > 0.01f) androidx.compose.ui.graphics.CompositingStrategy.Offscreen
        else androidx.compose.ui.graphics.CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        val a = amount().coerceIn(0f, 1f)
        if (a > 0.01f) {
            val h = height.toPx().coerceAtMost(size.height)
            drawRect(
                brush = Brush.verticalGradient(0f to Color.Black.copy(alpha = 1f - a), 1f to Color.Black, startY = 0f, endY = h),
                size = androidx.compose.ui.geometry.Size(size.width, h),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
            )
        }
    }

/**
 * Dissolves the bottom of a layer into whatever is behind it (real transparency, not a colour fill),
 * so artwork melts into the page background with no visible rectangular edge.
 */
fun Modifier.fadeOutBottom(from: Float = 0.6f): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(0f to Color.Black, from to Color.Black, 0.85f to Color.Black.copy(alpha = 0.35f), 1f to Color.Transparent),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
        )
    }

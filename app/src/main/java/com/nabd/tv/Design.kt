package com.nabd.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One visual identity for TV and phone: soft lavender canvas, frosted-glass cards, a navy pill for navigation,
 * sans headings with one serif accent word, rounded-2xl shapes and indigo-tinted shadows.
 * Light is the default; a dark variant uses the same tokens.
 */
object Ui {
    var dark by mutableStateOf(false)
}

val Accent: Color get() = if (Ui.dark) Color(0xFF8C8FFF) else Color(0xFF5456E8)
val OnAccent: Color get() = if (Ui.dark) Color(0xFF0B0D1F) else Color.White
val Bg: Color get() = if (Ui.dark) Color(0xFF0B0D1F) else Color(0xFFF1F2FD)
val Surf: Color get() = if (Ui.dark) Color(0xFF14183A) else Color.White
val Ink: Color get() = if (Ui.dark) Color.White else Color(0xFF14173A)
val TextSecondary: Color get() = if (Ui.dark) Color(0xFFA6ABCB) else Color(0xFF6B7094)
val Danger: Color get() = if (Ui.dark) Color(0xFFFF7A7F) else Color(0xFFE5484D)
val Ok: Color get() = if (Ui.dark) Color(0xFF3DDC9A) else Color(0xFF1FB37A)
val Warn: Color get() = if (Ui.dark) Color(0xFFFFC857) else Color(0xFFF5A524)
val GlassFill: Color get() = if (Ui.dark) Color(0x14FFFFFF) else Color(0xA8FFFFFF)
val GlassFocus: Color get() = if (Ui.dark) Color(0x2EFFFFFF) else Color(0xF2FFFFFF)
val GlassBorder: Color get() = if (Ui.dark) Color(0x24FFFFFF) else Color(0xE6FFFFFF)
val Hairline: Color get() = if (Ui.dark) Color(0x2EFFFFFF) else Color(0x1F14173A)
val Navy: Color get() = if (Ui.dark) Color(0xFF1B2060) else Color(0xFF161A4A)
val NavyDeep: Color get() = if (Ui.dark) Color(0xFF12164A) else Color(0xFF0E1235)

object Radius {
    val card = 22.dp
    val small = 16.dp
    val pill = 40.dp
}

val SerifFont: FontFamily = FontFamily.Serif

/** Page background: lavender wash with a soft glow in the corner (same on every screen size). */
fun Modifier.appBackground(): Modifier = this.drawBehind {
    drawRect(
        Brush.linearGradient(
            if (Ui.dark) listOf(Color(0xFF0B0D1F), Color(0xFF11143A), Color(0xFF0B0D1F))
            else listOf(Color(0xFFF8F8FF), Color(0xFFEAECFC), Color(0xFFDFE2F8)),
            start = Offset.Zero, end = Offset(size.width, size.height)
        )
    )
    drawCircle(
        Brush.radialGradient(
            listOf(Accent.copy(alpha = if (Ui.dark) 0.20f else 0.16f), Color.Transparent),
            center = Offset(size.width * 0.85f, size.height * 0.05f), radius = size.minDimension * 0.9f
        ),
        radius = size.minDimension * 0.9f, center = Offset(size.width * 0.85f, size.height * 0.05f)
    )
}

/** Indigo-tinted soft shadow (colored shadows apply on Android 9+; older versions get a neutral one). */
fun Modifier.softShadow(shape: Shape, elevation: Dp = 10.dp): Modifier =
    this.shadow(elevation, shape, clip = false, ambientColor = Accent.copy(alpha = 0.12f), spotColor = Accent.copy(alpha = 0.30f))

/** Frosted glass surface: translucent fill, white edge, optional focus/selected emphasis. */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(Radius.card),
    focused: Boolean = false,
    selected: Boolean = false
): Modifier = this
    .clip(shape)
    .background(
        when {
            focused -> Brush.verticalGradient(listOf(GlassFocus, GlassFocus))
            selected -> Brush.verticalGradient(listOf(Accent.copy(alpha = 0.20f), Accent.copy(alpha = 0.10f)))
            else -> Brush.verticalGradient(listOf(GlassFill, GlassFill.copy(alpha = GlassFill.alpha * 0.8f)))
        }
    )
    .border(
        if (focused) 3.dp else 1.dp,
        if (focused) Accent else if (selected) Accent.copy(alpha = 0.55f) else GlassBorder,
        shape
    )

/** Heading with one accent word in a serif face (italic for Latin text; bold upright for Arabic, where italics read badly). */
@Composable
fun AccentTitle(text: String, accent: String, size: TextUnit = 26.sp, modifier: Modifier = Modifier) {
    val latin = accent.none { it in '؀'..'ۿ' }
    Text(
        buildAnnotatedString {
            append(text)
            append(" ")
            withStyle(
                SpanStyle(
                    fontFamily = SerifFont, color = Accent,
                    fontStyle = if (latin) FontStyle.Italic else FontStyle.Normal,
                    fontWeight = if (latin) FontWeight.Normal else FontWeight.Bold
                )
            ) { append(accent) }
        },
        fontSize = size, fontWeight = FontWeight.Bold, color = Ink, maxLines = 2, modifier = modifier
    )
}

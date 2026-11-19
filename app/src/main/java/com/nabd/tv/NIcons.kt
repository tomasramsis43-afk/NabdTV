package com.nabd.tv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class Ic { Home, Live, Movies, Series, Guide, Rec, Settings, User, Search, Refresh, Star, Play, Dice, Close, Download }

/** One icon family for every screen size: 24-unit grid, 1.8 round strokes (same family as the reference's line icons). */
@Composable
fun NIcon(ic: Ic, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val st = Stroke(width = 1.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, o(x1, y1), o(x2, y2), st.width, StrokeCap.Round)
        fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) =
            drawRoundRect(tint, o(x, y), Size(w * s, h * s), CornerRadius(r * s), st)
        fun poly(vararg p: Float, close: Boolean = false, fill: Boolean = false) {
            val path = Path().apply {
                moveTo(p[0] * s, p[1] * s)
                for (i in 2 until p.size step 2) lineTo(p[i] * s, p[i + 1] * s)
                if (close) close()
            }
            drawPath(path, tint, style = if (fill) Fill else st)
        }
        fun ring(cx: Float, cy: Float, r: Float) = drawCircle(tint, r * s, o(cx, cy), style = st)
        fun dot(cx: Float, cy: Float, r: Float) = drawCircle(tint, r * s, o(cx, cy))

        when (ic) {
            Ic.Home -> { poly(3f, 11f, 12f, 3f, 21f, 11f); poly(5.5f, 9.5f, 5.5f, 20f, 10f, 20f, 10f, 14f, 14f, 14f, 14f, 20f, 18.5f, 20f, 18.5f, 9.5f) }
            Ic.Live -> { rr(3f, 7f, 18f, 13f, 3f); poly(8f, 2.5f, 12f, 7f, 16f, 2.5f) }
            Ic.Movies -> { rr(3f, 4f, 18f, 16f, 3.5f); poly(10f, 9f, 15.5f, 12f, 10f, 15f, close = true) }
            Ic.Series -> { line(9.5f, 6f, 21f, 6f); line(9.5f, 12f, 21f, 12f); line(9.5f, 18f, 21f, 18f); dot(4.5f, 6f, 1.4f); dot(4.5f, 12f, 1.4f); dot(4.5f, 18f, 1.4f) }
            Ic.Guide -> { rr(3f, 5f, 18f, 16f, 3f); line(3f, 10f, 21f, 10f); line(8f, 3f, 8f, 7f); line(16f, 3f, 16f, 7f) }
            Ic.Rec -> { ring(12f, 12f, 9f); dot(12f, 12f, 4.2f) }
            Ic.Settings -> {
                line(3f, 7f, 21f, 7f); line(3f, 12f, 21f, 12f); line(3f, 17f, 21f, 17f)
                dot(15f, 7f, 2.4f); dot(8f, 12f, 2.4f); dot(16f, 17f, 2.4f)
            }
            Ic.User -> { ring(12f, 8f, 3.6f); drawArc(tint, 180f, 180f, false, o(5f, 13.5f), Size(14f * s, 14f * s), style = st) }
            Ic.Search -> { ring(10.5f, 10.5f, 6.5f); line(15.5f, 15.5f, 21f, 21f) }
            Ic.Refresh -> { drawArc(tint, -50f, 290f, false, o(4f, 4f), Size(16f * s, 16f * s), style = st); poly(16f, 2.5f, 19.5f, 5.5f, 16f, 8.5f) }
            Ic.Star -> {
                val pts = FloatArray(20)
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 9.5f else 4.2f
                    val a = -PI / 2 + i * PI / 5
                    pts[i * 2] = 12f + (r * cos(a)).toFloat(); pts[i * 2 + 1] = 12.5f + (r * sin(a)).toFloat()
                }
                poly(*pts, close = true)
            }
            Ic.Play -> poly(7f, 4.5f, 19f, 12f, 7f, 19.5f, close = true, fill = true)
            Ic.Dice -> { rr(4f, 4f, 16f, 16f, 3.5f); dot(9f, 9f, 1.3f); dot(15f, 15f, 1.3f); dot(12f, 12f, 1.3f) }
            Ic.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Ic.Download -> { line(12f, 4f, 12f, 15f); poly(7f, 11f, 12f, 16f, 17f, 11f); line(5f, 20f, 19f, 20f) }
        }
    }
}

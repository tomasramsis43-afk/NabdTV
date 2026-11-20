package com.nabd.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Colours sampled from the artwork of the title that is currently presented.
 *
 * They are intentionally restrained: only the hero frame, its buttons, chips and the
 * shelf highlight use them. The page canvas stays a fixed near-black, so browsing never
 * turns into a rainbow. Missing / monochrome artwork falls back to the product accent.
 */
data class ArtworkPalette(
    val primary: Color,
    val secondary: Color,
    val accent: Color
) {
    companion object {
        fun fallback() = ArtworkPalette(AccentFill, Navy, Accent2)
    }
}

val ArtworkPalette.glow: Color get() = primary.copy(alpha = 0.38f)
val ArtworkPalette.soft: Color get() = primary.copy(alpha = 0.16f)

/**
 * Small on-device extractor. The image is decoded at thumbnail size (never the full backdrop),
 * sampled on a coarse grid, and pixels are clustered by hue with saturation * brightness as weight.
 * Results are cached per URL, so scrolling back and forth costs nothing.
 */
object ArtworkPaletteCache {
    private val cache = object : LruCache<String, ArtworkPalette>(64) {}
    private val misses = java.util.Collections.synchronizedSet(HashSet<String>())

    fun peek(url: String?): ArtworkPalette? = url?.let { cache.get(it) }

    suspend fun load(ctx: Context, url: String?): ArtworkPalette? {
        if (url.isNullOrBlank()) return null
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            cache.get(url)?.let { return@withContext it }
            if (!misses.add(url)) return@withContext null
            runCatching {
                val request = ImageRequest.Builder(ctx)
                    .data(url)
                    .size(88)
                    .allowHardware(false)
                    .build()
                val result = ctx.imageLoader.execute(request)
                val drawable = (result as? SuccessResult)?.drawable ?: return@runCatching null
                val bitmap = (drawable as? BitmapDrawable)?.bitmap
                    ?: drawable.toBitmap(64, 64, Bitmap.Config.ARGB_8888)
                extract(bitmap)?.also { cache.put(url, it) }
            }.getOrNull()
        }
    }

    private fun tune(hue: Float, sat: Float, value: Float): Color {
        val hsv = floatArrayOf(
            ((hue % 360f) + 360f) % 360f,
            sat.coerceIn(0.46f, 0.94f),
            value.coerceIn(0.30f, 0.94f)
        )
        val argb = android.graphics.Color.HSVToColor(hsv)
        return Color(argb)
    }

    private fun extract(bmp: Bitmap): ArtworkPalette? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 1 || h <= 1) return null

        val bins = FloatArray(24)
        val red = DoubleArray(24)
        val green = DoubleArray(24)
        val blue = DoubleArray(24)
        val hsv = FloatArray(3)

        val stepX = max(1, w / 44)
        val stepY = max(1, h / 44)
        var x = 0
        while (x < w) {
            var y = 0
            while (y < h) {
                val c = bmp.getPixel(x, y)
                if (android.graphics.Color.alpha(c) > 160) {
                    android.graphics.Color.colorToHSV(c, hsv)
                    val s = hsv[1]
                    val v = hsv[2]
                    if (s > 0.16f && v > 0.10f) {
                        val bin = (hsv[0] / 15f).toInt().coerceIn(0, 23)
                        val weight = s * (0.35f + v)
                        bins[bin] += weight
                        red[bin] += android.graphics.Color.red(c) * weight
                        green[bin] += android.graphics.Color.green(c) * weight
                        blue[bin] += android.graphics.Color.blue(c) * weight
                    }
                }
                y += stepY
            }
            x += stepX
        }

        var best = -1
        var bestWeight = 0f
        for (i in bins.indices) if (bins[i] > bestWeight) { bestWeight = bins[i]; best = i }
        if (best < 0 || bestWeight < 4f) return null

        val wsum = bins[best].toDouble().coerceAtLeast(0.0001)
        val hue = averageHue(red, green, blue, best, wsum)

        return ArtworkPalette(
            primary = tune(hue, 0.66f, 0.62f),
            secondary = tune(hue + 26f, 0.52f, 0.40f),
            accent = tune(hue - 14f, 0.72f, 0.90f)
        )
    }

    /** Circular average of the dominant bin; using blended RGB keeps warm/cool casts natural. */
    private fun averageHue(red: DoubleArray, green: DoubleArray, blue: DoubleArray, bin: Int, wsum: Double): Float {
        val r = (red[bin] / wsum).toInt().coerceIn(0, 255)
        val g = (green[bin] / wsum).toInt().coerceIn(0, 255)
        val b = (blue[bin] / wsum).toInt().coerceIn(0, 255)
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(android.graphics.Color.rgb(r, g, b), hsv)
        return hsv[0]
    }
}

/**
 * Palette of [url], animated when the user moves to another title.
 * The first frame of a brand-new URL shows the neutral fallback, then blends in the sampled colours.
 */
@Composable
fun rememberArtworkPalette(url: String?): ArtworkPalette {
    val ctx = LocalContext.current
    var loaded by remember(url) { mutableStateOf(ArtworkPaletteCache.peek(url)) }
    LaunchedEffect(url) {
        if (loaded == null) {
            // Small settle delay: D-pad scrolling does not launch a request for every poster it passes.
            kotlinx.coroutines.delay(180)
            loaded = ArtworkPaletteCache.load(ctx, url)
        }
    }
    val target = loaded ?: ArtworkPalette.fallback()
    val primary by animateColorAsState(target.primary, tween(620), label = "palPrimary")
    val secondary by animateColorAsState(target.secondary, tween(620), label = "palSecondary")
    val accent by animateColorAsState(target.accent, tween(620), label = "palAccent")
    return ArtworkPalette(primary, secondary, accent)
}

/** Tints a colour token for text drawn on top of artwork (keeps AA contrast on dark scrims). */
fun readableOnArt(color: Color): Color {
    val hsl = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsl)
    hsl[2] = hsl[2].coerceIn(0.82f, 0.97f)
    hsl[1] = hsl[1].coerceIn(0f, 0.55f)
    return Color(android.graphics.Color.HSVToColor(hsl))
}

/**
 * Deepens an artwork colour so it can be used as a filled button background:
 * at most 46% brightness keeps the white label above 4.5:1 contrast for every hue.
 */
fun buttonTone(color: Color): Color {
    val hsl = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsl)
    hsl[2] = hsl[2].coerceAtMost(0.46f)
    hsl[1] = hsl[1].coerceAtLeast(0.32f)
    return Color(android.graphics.Color.HSVToColor(hsl))
}

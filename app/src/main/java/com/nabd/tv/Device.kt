package com.nabd.tv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class FormFactor { Tv, Tablet, Phone }

fun isTelevision(ctx: Context): Boolean {
    val pm = ctx.packageManager
    val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        pm.hasSystemFeature("android.software.leanback") ||
        !pm.hasSystemFeature("android.hardware.touchscreen")
}

/**
 * What we are running on right now. Recomputed on rotation / resize, so layouts never keep stale assumptions.
 *
 * The sizes below are the density contract of the whole app: posters are deliberately compact
 * (more titles per row), while focus rings and controls stay big enough for a 10-foot UI.
 */
@Immutable
data class Device(val form: FormFactor, val widthDp: Int, val heightDp: Int) {
    val tv get() = form == FormFactor.Tv
    val touch get() = !tv
    val portrait get() = heightDp > widthDp
    val narrow get() = widthDp < 600

    /** Wide enough for side-by-side panes (category list + grid, channels + programmes). */
    val twoPane get() = widthDp >= 720

    val pad: Dp get() = if (tv) 32.dp else if (form == FormFactor.Tablet) 24.dp else 14.dp
    val gap: Dp get() = if (tv) 12.dp else 8.dp

    /** Minimum width of a poster cell in a grid. Smaller = more titles per row. */
    val posterMin: Dp get() = when {
        tv -> 106.dp
        widthDp >= 900 -> 104.dp
        widthDp >= 600 -> 98.dp
        else -> 86.dp
    }
    val liveMin: Dp get() = if (tv) 150.dp else if (widthDp >= 600) 138.dp else 116.dp

    /** Width of a poster in the horizontal home shelves. */
    val shelfPoster: Dp get() = if (tv) 104.dp else if (widthDp >= 600) 98.dp else 90.dp
    val shelfLive: Dp get() = if (tv) 140.dp else 124.dp

    val sideList: Dp get() = if (tv) 220.dp else 190.dp
    val control: Dp get() = if (tv) 44.dp else 46.dp

    /**
     * Height of the full-bleed cinematic hero on the home screen. It starts at the very top of the screen
     * (the floating header sits on top of it), so it is taller than a framed hero would be.
     * Derived from the real screen height so a shelf always stays visible underneath.
     */
    val heroHeight: Dp get() = when {
        tv -> (heightDp * 0.56f).dp.coerceIn(260.dp, 400.dp)
        widthDp >= 900 -> (heightDp * 0.52f).dp.coerceIn(260.dp, 400.dp)
        widthDp >= 600 -> (heightDp * 0.48f).dp.coerceIn(240.dp, 360.dp)
        portrait -> (heightDp * 0.40f).dp.coerceIn(280.dp, 380.dp)
        else -> (heightDp * 0.60f).dp.coerceIn(200.dp, 280.dp)
    }

    /** Columns for dashboard-style screens (settings). */
    val dashboardColumns: Int get() = when {
        tv -> 4
        widthDp >= 900 -> 4
        widthDp >= 600 -> 3
        else -> 2
    }
}

val LocalDevice = staticCompositionLocalOf { Device(FormFactor.Phone, 360, 800) }

@Composable
fun rememberDevice(): Device {
    val ctx = LocalContext.current
    val cfg = LocalConfiguration.current
    val tv = remember(ctx) { isTelevision(ctx) }
    return remember(tv, cfg.screenWidthDp, cfg.screenHeightDp, cfg.smallestScreenWidthDp) {
        Device(
            when {
                tv -> FormFactor.Tv
                cfg.smallestScreenWidthDp >= 600 -> FormFactor.Tablet
                else -> FormFactor.Phone
            },
            cfg.screenWidthDp, cfg.screenHeightDp
        )
    }
}

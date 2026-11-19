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

/** How the main navigation is laid out. */
enum class NavMode { RailFull, RailCompact, Bottom }

fun isTelevision(ctx: Context): Boolean {
    val pm = ctx.packageManager
    val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        pm.hasSystemFeature("android.software.leanback") ||
        !pm.hasSystemFeature("android.hardware.touchscreen")
}

/** What we are running on right now. Recomputed on rotation / resize, so layouts never keep stale assumptions. */
@Immutable
data class Device(val form: FormFactor, val widthDp: Int, val heightDp: Int) {
    val tv get() = form == FormFactor.Tv
    val touch get() = !tv
    val portrait get() = heightDp > widthDp

    val nav: NavMode
        get() = when {
            tv -> NavMode.RailFull
            portrait -> NavMode.Bottom
            heightDp >= 600 -> NavMode.RailFull      // tablet, landscape
            else -> NavMode.RailCompact              // phone, landscape: icons only
        }

    /** Wide enough for side-by-side panes (category list + grid, channels + programmes). */
    val twoPane get() = widthDp >= 720

    val pad: Dp get() = if (tv) 32.dp else if (form == FormFactor.Tablet) 24.dp else 14.dp
    val gap: Dp get() = if (tv) 14.dp else 10.dp
    val liveMin: Dp get() = if (tv) 164.dp else if (widthDp >= 600) 150.dp else 128.dp
    val posterMin: Dp get() = if (tv) 132.dp else if (widthDp >= 600) 128.dp else 104.dp
    val shelfLive: Dp get() = if (tv) 168.dp else 140.dp
    val shelfPoster: Dp get() = if (tv) 132.dp else 108.dp
    val sideList: Dp get() = if (tv) 220.dp else 190.dp
    val control: Dp get() = if (tv) 44.dp else 48.dp     // minimum touch / focus target height
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

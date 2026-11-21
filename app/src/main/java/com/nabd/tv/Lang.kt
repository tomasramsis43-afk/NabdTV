package com.nabd.tv

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * In-app interface language ("ar" default, or "en"). It is a Compose state, so switching re-renders the screens
 * at once without recreating the activity — playback and user data are untouched.
 */
object Lang {
    var code by mutableStateOf("ar"); private set
    val rtl: Boolean get() = code == "ar"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("nabd_lang", Context.MODE_PRIVATE)

    /** Application context for [S]; set once in [load]. */
    lateinit var appCtx: Context; private set

    fun load(ctx: Context) {
        appCtx = ctx.applicationContext
        code = prefs(ctx).getString("lang", "ar").takeIf { it == "ar" || it == "en" } ?: "ar"
    }

    fun set(ctx: Context, c: String) {
        if (c != "ar" && c != "en") return
        prefs(ctx).edit().putString("lang", c).apply()
        code = c
    }

    /** Non-composable lookup (ViewModel, services). Reads [code], so derived state re-evaluates on switch. */
    fun str(ctx: Context, @StringRes id: Int, vararg args: Any): String {
        val r = resources(ctx, code)
        return if (args.isEmpty()) r.getString(id) else r.getString(id, *args)
    }

    /** Resources for [c], independent of the device locale. Arabic keeps Latin digits like the rest of the UI. */
    fun resources(ctx: Context, c: String): Resources {
        val cfg = android.content.res.Configuration(ctx.resources.configuration)
        cfg.setLocale(if (c == "ar") Locale.forLanguageTag("ar-u-nu-latn") else Locale.ENGLISH)
        return ctx.createConfigurationContext(cfg).resources
    }
}

/** String resource in the selected interface language. */
@Composable
fun tr(@StringRes id: Int, vararg args: Any): String {
    val ctx = LocalContext.current
    val code = Lang.code
    val res = remember(ctx, code) { Lang.resources(ctx, code) }
    return if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
}

/**
 * String resource in the selected language from any context (composable, click handler, ViewModel).
 * It reads [Lang.code], so composables that call it re-render when the language is switched.
 */
fun S(@StringRes id: Int, vararg args: Any): String = Lang.str(Lang.appCtx, id, *args)

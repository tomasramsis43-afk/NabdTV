package com.nabd.tv

import androidx.compose.runtime.Composable
import androidx.tv.material3.ExperimentalTvMaterial3Api

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = Ui.dark
    androidx.tv.material3.MaterialTheme(
        colorScheme = if (dark) androidx.tv.material3.darkColorScheme(
            primary = AccentFill, onPrimary = OnAccent, background = Bg, onBackground = Ink,
            surface = Surf, onSurface = Ink, surfaceVariant = SurfHigh, onSurfaceVariant = TextSecondary,
        ) else androidx.tv.material3.lightColorScheme(
            primary = AccentFill, onPrimary = OnAccent, background = Bg, onBackground = Ink,
            surface = Surf, onSurface = Ink, surfaceVariant = SurfHigh, onSurfaceVariant = TextSecondary,
        )
    ) {
        androidx.compose.material3.MaterialTheme(
            colorScheme = if (dark) androidx.compose.material3.darkColorScheme(
                primary = AccentFill, onPrimary = OnAccent, background = Bg, onBackground = Ink,
                surface = Surf, onSurface = Ink, surfaceVariant = SurfHigh, onSurfaceVariant = TextSecondary,
            ) else androidx.compose.material3.lightColorScheme(
                primary = AccentFill, onPrimary = OnAccent, background = Bg, onBackground = Ink,
                surface = Surf, onSurface = Ink, surfaceVariant = SurfHigh, onSurfaceVariant = TextSecondary,
            ),
            content = {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.tv.material3.LocalContentColor provides Ink,
                    androidx.compose.material3.LocalContentColor provides Ink,
                    LocalDevice provides rememberDevice(),
                    // The whole interface is Arabic, so it must read right-to-left even on a TV box
                    // that was set up with an English system locale. Screens that hold technical,
                    // directional content (player, setup, some VOD lists) opt back into LTR locally.
                    androidx.compose.ui.platform.LocalLayoutDirection provides (if (Lang.rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr),
                    content = content
                )
            }
        )
    }
}

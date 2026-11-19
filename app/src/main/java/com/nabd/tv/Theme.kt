package com.nabd.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = Ui.dark
    androidx.tv.material3.MaterialTheme(
        colorScheme = if (dark) androidx.tv.material3.darkColorScheme(
            primary = Accent, onPrimary = OnAccent, background = Bg, onBackground = Ink,
            surface = Surf, onSurface = Ink, surfaceVariant = Navy, onSurfaceVariant = TextSecondary,
        ) else androidx.tv.material3.lightColorScheme(
            primary = Accent, onPrimary = OnAccent, background = Bg, onBackground = Ink,
            surface = Surf, onSurface = Ink, surfaceVariant = Color(0xFFE6E8FA), onSurfaceVariant = TextSecondary,
        )
    ) {
        androidx.compose.material3.MaterialTheme(
            colorScheme = if (dark) androidx.compose.material3.darkColorScheme(
                primary = Accent, onPrimary = OnAccent, background = Bg, surface = Surf, onSurface = Ink,
            ) else androidx.compose.material3.lightColorScheme(
                primary = Accent, onPrimary = OnAccent, background = Bg, surface = Surf, onSurface = Ink,
            ),
            content = {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.tv.material3.LocalContentColor provides Ink,
                    androidx.compose.material3.LocalContentColor provides Ink,
                    LocalDevice provides rememberDevice(),
                    content = content
                )
            }
        )
    }
}

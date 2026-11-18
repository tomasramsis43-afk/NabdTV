package com.nabd.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api

val Accent = Color(0xFF00E5A0)
val Bg = Color(0xFF0B0F14)
val Surf = Color(0xFF151C26)
val TextSecondary = Color(0xFFC7D0DB)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    androidx.tv.material3.MaterialTheme(
        colorScheme = androidx.tv.material3.darkColorScheme(
            primary = Accent, onPrimary = Color.Black,
            background = Bg, onBackground = Color.White,
            surface = Surf, onSurface = Color.White,
            surfaceVariant = Color(0xFF1E2733), onSurfaceVariant = Color(0xFFB8C2CF),
        )
    ) {
        androidx.compose.material3.MaterialTheme(
            colorScheme = androidx.compose.material3.darkColorScheme(
                primary = Accent, onPrimary = Color.Black, background = Bg, surface = Surf,
            ),
            content = {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.tv.material3.LocalContentColor provides Color.White,
                    androidx.compose.material3.LocalContentColor provides Color.White,
                    content = content
                )
            }
        )
    }
}

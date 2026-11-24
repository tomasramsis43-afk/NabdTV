package com.nabd.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

/** Verified official logo for a title, or null while loading / when none exists. */
@Composable
fun rememberTitleLogo(title: String, kind: Kind, year: Int? = null): File? {
    if (kind == Kind.Live) return null
    val st = produceState(initialValue = Logos.cached(title, kind, year), title, kind, year) {
        value = Logos.get(title, kind, year)
    }
    return st.value
}

/** Same lookup, plus whether it has finished (a logo, or a definite "none"), so callers can wait instead of flashing a fallback. */
@Composable
fun rememberTitleLogoState(title: String, kind: Kind, year: Int? = null): Pair<File?, Boolean> {
    if (kind == Kind.Live) return null to true
    val first = Logos.cached(title, kind, year)
    val st = produceState(initialValue = first to (first != null), title, kind, year) {
        value = Logos.get(title, kind, year) to true
    }
    return st.value
}

/**
 * Shows the official logo in a [height] box, or [fallback] (the plain title in the existing text style)
 * when no verified logo exists — never an empty box or a broken image.
 */
@Composable
fun TitleLogo(
    title: String, kind: Kind, year: Int?, height: Dp, modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.CenterStart, fallback: @Composable () -> Unit
) {
    val (f, settled) = rememberTitleLogoState(title, kind, year)
    var failed by remember(f) { mutableStateOf(false) }
    // While the lookup is pending the slot stays empty (no title text that is then swapped for the logo); after 2 s the plain title is used.
    var gaveUp by remember(title) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(title) { kotlinx.coroutines.delay(2000); gaveUp = true }
    if (!settled && !gaveUp) { Box(modifier.fillMaxWidth().height(height)); return }
    Crossfade(targetState = f != null && !failed, animationSpec = tween(250), label = "titleLogo") { show ->
        if (show && f != null) {
            // Wide wordmarks (pixel fonts, long names) must not cover the artwork: cap the width as well as the height.
            val maxW = if (LocalDevice.current.tv) 380.dp else 280.dp
            Box(modifier.widthIn(max = maxW).fillMaxWidth().height(height)) {
                AsyncImage(
                    model = f, contentDescription = title, contentScale = ContentScale.Fit, alignment = alignment,
                    onError = { failed = true }, modifier = Modifier.fillMaxSize()
                )
            }
        } else fallback()
    }
}

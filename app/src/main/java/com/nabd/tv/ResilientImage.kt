package com.nabd.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Walks through candidate picture urls until one loads: a failed load is retried once (transient network errors),
 * then the next candidate is tried. When all of them fail, [exhausted] is true and the caller shows [ArtPlaceholder]
 * instead of an empty frame.
 */
@Stable
class ImageChain(private val urls: List<String>, private val scope: CoroutineScope) {
    private var index by mutableIntStateOf(0)
    private var attempt by mutableIntStateOf(0)
    private var retried = false

    val url: String? get() = urls.getOrNull(index)
    val exhausted: Boolean get() = index >= urls.size

    /** Call from AsyncImage onError. */
    fun fail() {
        if (!retried) {
            retried = true
            val at = index
            scope.launch { delay(1500); if (index == at) attempt++ }
        } else {
            retried = false
            index++
        }
    }

    @Composable
    fun request(): ImageRequest? {
        val u = url ?: return null
        val ctx = LocalContext.current
        val a = attempt
        return remember(u, a) {
            ImageRequest.Builder(ctx).data(u).setParameter("retry", a, memoryCacheKey = null).build()
        }
    }
}

@Composable
fun rememberImageChain(urls: List<String>): ImageChain {
    val scope = rememberCoroutineScope()
    return remember(urls) { ImageChain(urls, scope) }
}

/** Shown when no picture could be loaded: the title on the card colour, so the card is never an empty box. */
@Composable
fun ArtPlaceholder(title: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(SurfHigh), contentAlignment = Alignment.Center) {
        Text(
            title, color = TextSecondary, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(10.dp)
        )
    }
}

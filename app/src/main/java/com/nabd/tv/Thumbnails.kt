package com.nabd.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Generates scrub preview thumbnails for local files (recordings and downloaded media).
 * Network streams do not expose random access frames reliably, so they show a time bubble instead.
 */
class ThumbnailLoader private constructor(
    private val ctx: Context,
    private val uri: Uri
) {
    private val mutex = Mutex()
    private var retriever: MediaMetadataRetriever? = null
    private var rotation = 0
    private var failed = false
    private val cache = object : LruCache<Long, Bitmap>(16) {}

    private fun ensure(): MediaMetadataRetriever? {
        retriever?.let { return it }
        if (failed) return null
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(ctx, uri)
            rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            retriever = r
            r
        } catch (e: Exception) {
            failed = true
            null
        }
    }

    suspend fun frame(timeMs: Long): Bitmap? = withContext(Dispatchers.IO) {
        val key = timeMs.coerceAtLeast(0) / 1000 * 1000
        cache.get(key)?.let { return@withContext it }
        mutex.withLock {
            cache.get(key)?.let { return@withLock it }
            val r = ensure() ?: return@withLock null
            val bmp = try {
                r.getFrameAtTime(key * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (e: Exception) {
                null
            } ?: return@withLock null
            val scaled = scaleAndRotate(bmp, rotation)
            cache.put(key, scaled)
            scaled
        }
    }

    fun close() {
        runCatching { retriever?.release() }
        retriever = null
        cache.evictAll()
    }

    private fun scaleAndRotate(src: Bitmap, degrees: Int): Bitmap {
        val maxW = 320
        val scale = if (src.width > maxW) maxW.toFloat() / src.width else 1f
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (degrees != 0) postRotate(degrees.toFloat())
        }
        if (scale == 1f && degrees == 0) return src
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (out !== src) src.recycle()
        return out
    }

    companion object {
        fun create(ctx: Context, uri: String): ThumbnailLoader? {
            if (!(uri.startsWith("file://") || uri.startsWith("content://"))) return null
            return ThumbnailLoader(ctx, Uri.parse(uri))
        }
    }
}

@Composable
fun rememberThumbnailLoader(uri: String): ThumbnailLoader? {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val loader = remember(uri) { ThumbnailLoader.create(ctx, uri) }
    DisposableEffect(loader) { onDispose { loader?.close() } }
    return loader
}

/** The picture shown above the timeline while scrubbing. Returns False when no frame is available. */
@Composable
fun ScrubThumbnail(loader: ThumbnailLoader?, timeMs: Long, modifier: Modifier = Modifier): Boolean {
    var bmp by remember { mutableStateOf<ImageBitmap?>(null) }
    var lastGood by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(loader, timeMs) {
        if (loader == null) { bmp = null; return@LaunchedEffect }
        delay(110)   // debounce: scrub fast, fetch only the settled frame
        val f = loader.frame(timeMs)
        if (f != null) lastGood = f.asImageBitmap()
        bmp = lastGood
    }
    val image = bmp ?: lastGood
    if (image != null) {
        Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
            Image(
                bitmap = image, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.width(192.dp).height(108.dp)
            )
        }
        return true
    }
    return false
}

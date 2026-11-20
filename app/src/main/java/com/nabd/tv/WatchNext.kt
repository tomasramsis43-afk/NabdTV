package com.nabd.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.BaseColumns
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram

/**
 * Publishes the titles the user is in the middle of to the Android TV home screen ("Watch Next" row),
 * so a movie or episode can be resumed straight from the launcher. Every call is best effort: on devices
 * without a TV provider (phones, boxes with a stripped launcher) it silently does nothing.
 */
object WatchNext {
    private const val SCHEME = "nabdtv"
    private val lastSync = HashMap<String, Long>()

    private fun intentFor(ctx: Context, url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME://resume?u=" + Uri.encode(url)))
            .setPackage(ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** The media url a Watch Next tile asked us to resume, or null for any other intent. */
    fun urlFrom(intent: Intent?): String? =
        intent?.data?.takeIf { it.scheme == SCHEME }?.getQueryParameter("u")

    private fun key(url: String) = url.hashCode().toString()

    private fun findId(ctx: Context, key: String): Long? {
        ctx.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            arrayOf(BaseColumns._ID, TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == key) return c.getLong(0)
        }
        return null
    }

    fun remove(ctx: Context, url: String) {
        runCatching {
            findId(ctx, key(url))?.let { ctx.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null) }
            lastSync.remove(url)
        }
    }

    fun clearAll(ctx: Context) {
        runCatching { ctx.contentResolver.delete(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null) }
        lastSync.clear()
    }

    /** Adds / refreshes the tile for [h]; finished or barely started titles are removed instead. */
    fun sync(ctx: Context, h: HistoryEntry) {
        if (!isTelevision(ctx) || h.kind == Kind.Live) return
        if (h.finished || h.progress <= 0.02f) { remove(ctx, h.url); return }
        val now = System.currentTimeMillis()
        if (now - (lastSync[h.url] ?: 0L) < 30_000L) return   // saveProgress runs often; the launcher does not need every tick
        lastSync[h.url] = now
        runCatching {
            val b = WatchNextProgram.Builder()
                .setType(if (h.kind == Kind.Series) TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(h.last)
                .setTitle(h.name)
                .setLastPlaybackPositionMillis(h.position.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                .setDurationMillis(h.duration.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                .setInternalProviderId(key(h.url))
                .setIntent(intentFor(ctx, h.url))
            h.logo?.takeIf { it.startsWith("http") }?.let {
                b.setPosterArtUri(Uri.parse(it)).setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
            }
            val values = b.build().toContentValues()
            val id = findId(ctx, key(h.url))
            if (id != null) ctx.contentResolver.update(TvContractCompat.buildWatchNextProgramUri(id), values, null, null)
            else ctx.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
        }
    }
}

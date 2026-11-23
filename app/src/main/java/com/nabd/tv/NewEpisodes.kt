package com.nabd.tv

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Tells the user when a favourite series gets new episodes. Runs inside the periodic background refresh:
 * the episode count of every favourite series is compared with the count seen last time.
 * The first run only records the counts, so nothing is announced for episodes that already existed.
 */
object NewEpisodes {
    private const val CH = "nabd_new_episodes"

    suspend fun check(ctx: Context, store: Store, repo: Repo) {
        if (!store.notifyNewEpisodes || !repo.hasXtream) return
        // Followed = favourites + series watched in the last 60 days + Watch later.
        val since = System.currentTimeMillis() - 60L * 86_400_000L
        val watched = store.history().filter { it.kind == Kind.Series && it.last >= since }.mapNotNull { store.epMeta(it.url)?.seriesUrl }
        val favs = VmLogic.followedSeries(store.favorites, watched, store.watchLater)
        if (favs.isEmpty()) return
        val series = runCatching { repo.loadSeries(false) }.getOrDefault(emptyList()).associateBy { it.url }
        for (url in favs) {
            val c = series[url] ?: continue
            val id = url.removePrefix("series:").toIntOrNull() ?: continue
            val eps = runCatching { repo.loadEpisodes(id) }.getOrNull() ?: continue
            if (eps.isEmpty()) continue
            val prev = store.knownEpisodeCount(url)
            store.setKnownEpisodeCount(url, eps.size)
            if (prev > 0 && eps.size > prev) notify(ctx, c, eps.size - prev, VmLogic.newestEpisode(eps))
        }
    }

    private fun notify(ctx: Context, c: Channel, added: Int, newest: Episode?) {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, S(R.string.a_007e9376), NotificationManager.IMPORTANCE_DEFAULT))
            val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            val pi = open?.let { PendingIntent.getActivity(ctx, c.url.hashCode(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
            val base = if (added == 1) S(R.string.a_8d8f4720) else S(R.string.x_ne_many, added)
            val text = newest?.let { base + " · " + S(R.string.x_ne_latest, S(R.string.ep_label, it.season, it.num)) } ?: base
            val n = NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(c.name)
                .setContentText(text)
                .setAutoCancel(true)
                .apply { if (pi != null) setContentIntent(pi) }
                .build()
            nm.notify(c.url.hashCode(), n)
        }
    }
}

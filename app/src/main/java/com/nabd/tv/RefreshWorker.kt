package com.nabd.tv

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Background upkeep: refresh playlist + guide, re-check favourites, schedule auto-recordings. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        if (!store.autoRefresh) return Result.success()
        val repo = Repo(applicationContext, store)
        if (repo.playlistUrl.isBlank()) return Result.success()
        return try {
            val channels = repo.load(true)
            val epg = repo.loadEpg(channels, true)
            val favs = store.favorites
            val favUrls = channels.filter { it.url in favs }.map { it.url }.take(40)
            // concurrency 1: never compete with the user's own stream for the account's connection limit
            if (favUrls.isNotEmpty()) HealthChecker(repo, store).check(favUrls, 1)
            Recorder.syncAuto(applicationContext, store, channels, epg)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("nabd_refresh", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

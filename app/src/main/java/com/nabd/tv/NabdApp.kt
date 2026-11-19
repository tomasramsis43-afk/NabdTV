package com.nabd.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

class NabdApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }

    /** Posters/logos are cached on disk, so scrolling big lists stays smooth and works offline. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(180)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(File(cacheDir, "img")).maxSizeBytes(250L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .build()
}

/** Keeps the last uncaught exceptions in a file so problems found while testing can be reported. */
object CrashLog {
    private fun file(ctx: android.content.Context) = File(ctx.filesDir, "crash.txt")

    fun install(ctx: android.content.Context) {
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val f = file(ctx)
                val keep = if (f.exists()) f.readText().takeLast(6000) else ""
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                f.writeText(keep + "\n=== $stamp [${t.name}] ===\n" + android.util.Log.getStackTraceString(e).take(3500))
            }
            old?.uncaughtException(t, e)
        }
    }

    fun read(ctx: android.content.Context): String = runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()
    fun clear(ctx: android.content.Context) { runCatching { file(ctx).delete() } }
}

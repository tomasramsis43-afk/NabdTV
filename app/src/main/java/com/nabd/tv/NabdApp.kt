package com.nabd.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class NabdApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Lang.load(this)
        Logos.init(this)
        Artwork.init(this)
        ArtStore.init(this)
        TmdbRatings.init(this)
        Store(this).let { st -> Logos.tmdbKey = { st.tmdbKey }; Artwork.tmdbKey = { st.tmdbKey } }
        CrashLog.install(this)
        CrashReporter.sendPendingAsync(this)
        UpdateWorker.sync(this)   // cancels the background updater unless the user enabled it
    }

    /** Posters/logos are cached on disk, so scrolling big lists stays smooth and works offline. */
    override fun newImageLoader(): ImageLoader {
        // Low-RAM TV boxes: smaller memory cache and 16-bit posters (half the memory per image).
        val low = (getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager)?.isLowRamDevice == true
        return buildLoader(low)
    }

    private fun buildLoader(low: Boolean): ImageLoader = ImageLoader.Builder(this)
        .crossfade(180)
        .allowRgb565(low)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(if (low) 0.1 else 0.2).build() }
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
                val trace = Scrub.clean(android.util.Log.getStackTraceString(e)).take(3500)
                f.writeText(keep + "\n=== $stamp [${t.name}] ===\n" + trace)
                // Show the error on screen from a separate process (not when the crash happened inside that process).
                if (!isCrashProcess()) {
                    ctx.startActivity(
                        android.content.Intent(ctx, CrashActivity::class.java)
                            .putExtra("trace", "[$stamp] " + trace)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    Thread.sleep(500)
                }
            }
            old?.uncaughtException(t, e)
        }
    }

    private fun isCrashProcess() = runCatching { processName().endsWith(":crash") }.getOrDefault(false)

    /** Application.getProcessName needs API 28; older devices read the name from /proc. */
    private fun processName(): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
        else java.io.File("/proc/self/cmdline").readText().trim { it == '\u0000' || it.isWhitespace() }

    fun read(ctx: android.content.Context): String = runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()
    fun clear(ctx: android.content.Context) { runCatching { file(ctx).delete() } }
}

/**
 * Optional crash reporting without an SDK: if the user set a Sentry DSN in Settings, the crash log saved by [CrashLog]
 * is sent once to that project (plain HTTPS POST to the store endpoint) and then cleared. Nothing is sent without a DSN.
 */
object CrashReporter {
    fun sendPendingAsync(ctx: android.content.Context) {
        val app = ctx.applicationContext
        Thread {
            runCatching {
                val dsn = Store(app).sentryDsn
                val text = Scrub.clean(CrashLog.read(app))
                if (dsn.isBlank() || text.isBlank()) return@runCatching
                val u = java.net.URI(dsn)
                val key = u.userInfo?.substringBefore(':') ?: return@runCatching
                val project = u.path.trim('/').substringAfterLast('/')
                if (key.isBlank() || project.isBlank()) return@runCatching
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
                val body = org.json.JSONObject()
                    .put("message", text.takeLast(3500))
                    .put("level", "fatal")
                    .put("platform", "java")
                    .put("release", "nabdtv@$version")
                    .put("timestamp", System.currentTimeMillis() / 1000)
                    .put("tags", org.json.JSONObject().put("android", android.os.Build.VERSION.SDK_INT.toString()).put("device", android.os.Build.MODEL))
                val port = if (u.port > 0) ":" + u.port else ""
                val req = okhttp3.Request.Builder()
                    .url("${u.scheme}://${u.host}$port/api/$project/store/")
                    .header("X-Sentry-Auth", "Sentry sentry_version=7, sentry_client=nabdtv/1.0, sentry_key=$key")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                Net.client(10, 15).newCall(req).execute().use { r -> if (r.isSuccessful) CrashLog.clear(app) }
            }
        }.start()
    }
}

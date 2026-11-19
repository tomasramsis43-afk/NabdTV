package com.nabd.tv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Whether the UI is on screen (a silent update must never kill the app while someone is watching). */
object AppState { @Volatile var foreground = false }

/** Settings for automatic updates (kept in their own file so the background worker can read them). */
object UpdatePrefs {
    private fun p(c: Context) = c.getSharedPreferences("nabd_update", Context.MODE_PRIVATE)
    fun auto(c: Context) = p(c).getBoolean("auto", true)
    fun setAuto(c: Context, v: Boolean) = p(c).edit().putBoolean("auto", v).apply()
    fun lastTried(c: Context) = p(c).getInt("tried", 0)
    fun triedAt(c: Context) = p(c).getLong("triedAt", 0L)
    fun setTried(c: Context, build: Int) = p(c).edit().putInt("tried", build).putLong("triedAt", System.currentTimeMillis()).apply()
}

object Notifier {
    private const val CH = "updates"

    fun show(ctx: Context, id: Int, title: String, text: String, pi: PendingIntent?) {
        if (Build.VERSION.SDK_INT >= 26) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(CH, "التحديثات", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val b = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text).setAutoCancel(true)
        if (pi != null) b.setContentIntent(pi)
        runCatching { NotificationManagerCompat.from(ctx).notify(id, b.build()) }
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

/** Checks the repo's latest release and installs it by itself (used by the background worker). */
object AutoUpdate {
    /** @return true when an install was started. */
    suspend fun run(ctx: Context, http: OkHttpClient): Boolean {
        if (!UpdatePrefs.auto(ctx)) return false
        if (PlaybackBus.active || AppState.foreground) return false          // never interrupt a stream or the UI
        val rel = Updater.check(ctx, http) ?: return false
        // The same build is retried at most every 3 hours (avoids loops when installs are blocked).
        if (UpdatePrefs.lastTried(ctx) == rel.build && System.currentTimeMillis() - UpdatePrefs.triedAt(ctx) < 3 * 3600_000L) return false
        UpdatePrefs.setTried(ctx, rel.build)
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            Notifier.show(ctx, 7001, "تحديث جديد ${rel.tag}", "اسمح لـ Nabd TV بالتثبيت من الإعدادات لتفعيل التحديث التلقائي", Notifier.openApp(ctx))
            return false
        }
        val apk = Updater.download(ctx, http, rel) { }
        return Updater.installSession(ctx, apk)
    }
}

/** Polls the repo every ~15 minutes while the device is online. */
class UpdateWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        runCatching { AutoUpdate.run(applicationContext, OkHttpClient()) }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("nabd-update", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** Result of a PackageInstaller session: asks for confirmation when Android requires it, reports failures. */
class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (AppState.foreground) runCatching { ctx.startActivity(confirm) }
                else Notifier.show(
                    ctx, 7001, "تحديث جاهز للتثبيت", "اضغط للتأكيد",
                    PendingIntent.getActivity(ctx, 7, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                )
            }
            PackageInstaller.STATUS_SUCCESS -> Unit      // Android restarts the process with the new version
            else -> Notifier.show(
                ctx, 7001, "تعذّر التحديث", i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "حاول مرة أخرى من الإعدادات",
                Notifier.openApp(ctx)
            )
        }
    }
}

/** After an update, tell the user the new version is ready. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(7001) }
        Notifier.show(ctx, 7002, "تم تحديث Nabd TV", "الإصدار ${Updater.currentName(ctx)} جاهز، اضغط للفتح", Notifier.openApp(ctx))
    }
}

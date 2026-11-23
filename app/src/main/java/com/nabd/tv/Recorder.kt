package com.nabd.tv

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.AlarmManagerCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/** Scheduling side of the DVR: recordings and reminders are persisted in [Store] and fired by AlarmManager. */
object Recorder {
    const val ACTION_RECORD = "com.nabd.tv.RECORD"
    const val ACTION_REMIND = "com.nabd.tv.REMIND"
    const val CHANNEL_ID = "nabd_rec"
    private const val PRE_ROLL = 60_000L
    private const val POST_ROLL = 120_000L
    val lock = Any()

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, S(R.string.a_65278c12), NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun pending(ctx: Context, action: String, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id.hashCode(),
            Intent(ctx, AlarmReceiver::class.java).setAction(action).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun setAlarm(ctx: Context, at: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val t = maxOf(at, System.currentTimeMillis() + 1000)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
                AlarmManagerCompat.setAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, t, pi)
            else AlarmManagerCompat.setExactAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, t, pi)
        } catch (e: SecurityException) {
            AlarmManagerCompat.setAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, t, pi)
        }
    }

    private fun update(store: Store, id: String, f: (Recording) -> Recording) = synchronized(lock) {
        store.saveRecordings(store.recordings().map { if (it.id == id) f(it) else it })
    }

    /** Overlapping jobs (providers often allow only 1–2 simultaneous connections). */
    fun conflicts(store: Store, start: Long, stop: Long): List<Recording> =
        store.recordings().filter {
            (it.status == RecStatus.Scheduled || it.status == RecStatus.Recording) && it.start < stop && it.stop > start
        }

    /** @return the created job, or null if this programme is already scheduled. */
    fun schedule(ctx: Context, store: Store, c: Channel, p: Prog): Recording? = synchronized(lock) {
        val id = "${c.url.hashCode()}_${p.start}"
        val list = store.recordings()
        if (list.any { it.id == id && it.status != RecStatus.Cancelled }) return null
        val rec = Recording(id, c.name, c.url, p.title, p.start - PRE_ROLL, p.stop + POST_ROLL, RecStatus.Scheduled)
        store.saveRecordings(list.filter { it.id != id } + rec)
        ensureChannel(ctx)
        setAlarm(ctx, rec.start, pending(ctx, ACTION_RECORD, id))
        rec
    }

    fun cancel(ctx: Context, store: Store, id: String) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx, ACTION_RECORD, id))
        update(store, id) { if (it.status == RecStatus.Done) it else it.copy(status = RecStatus.Cancelled) }
    }

    fun delete(ctx: Context, store: Store, id: String) {
        cancel(ctx, store, id)
        synchronized(lock) {
            val list = store.recordings()
            list.firstOrNull { it.id == id }?.file?.let { runCatching { File(it).delete() } }
            store.saveRecordings(list.filter { it.id != id })
        }
    }

    fun markFailed(ctx: Context, id: String) = update(Store(ctx), id) { it.copy(status = RecStatus.Failed) }

    fun rescheduleAll(ctx: Context) {
        val store = Store(ctx)
        val now = System.currentTimeMillis()
        store.recordings().filter { it.status == RecStatus.Scheduled && it.stop > now }.forEach {
            setAlarm(ctx, it.start, pending(ctx, ACTION_RECORD, it.id))
        }
        store.reminders().filter { it.start > now }.forEach { setAlarm(ctx, it.start - 120_000L, pending(ctx, ACTION_REMIND, it.id)) }
    }

    /** Auto-record: every upcoming airing of a favourite title on a favourite channel. */
    fun syncAuto(ctx: Context, store: Store, channels: List<Channel>, epg: Map<String, List<Prog>>) {
        val titles = store.autoRecordTitles
        if (titles.isEmpty()) return
        val favs = store.favorites
        val now = System.currentTimeMillis()
        for (c in channels) {
            if (c.url !in favs) continue
            for (p in epg[c.url] ?: continue) {
                if (p.start > now && p.title.lowercase() in titles) schedule(ctx, store, c, p)
            }
        }
    }

    fun remind(ctx: Context, store: Store, c: Channel, p: Prog) {
        val id = "${c.url.hashCode()}_${p.start}"
        synchronized(lock) {
            val list = store.reminders()
            if (list.any { it.id == id }) return
            store.saveReminders(list + Reminder(id, c.name, p.title, p.start))
        }
        ensureChannel(ctx)
        setAlarm(ctx, p.start - 120_000L, pending(ctx, ACTION_REMIND, id))
    }

    fun notifyReminder(ctx: Context, id: String) {
        val r = Store(ctx).reminders().firstOrNull { it.id == id } ?: return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(S(R.string.x_rec_starting, r.title))
            .setContentText(r.channelName)
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id.hashCode(), n) }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            Recorder.ACTION_RECORD -> {
                val id = i.getStringExtra("id") ?: return
                try {
                    ContextCompat.startForegroundService(ctx, Intent(ctx, RecordService::class.java).putExtra("id", id))
                } catch (e: Exception) {
                    Recorder.markFailed(ctx, id)
                }
            }
            Recorder.ACTION_REMIND -> Recorder.notifyReminder(ctx, i.getStringExtra("id") ?: return)
            Intent.ACTION_BOOT_COMPLETED -> Recorder.rescheduleAll(ctx)
        }
    }
}

/** Foreground service that copies the live stream (raw TS or HLS segments) into a local file. */
class RecordService : Service() {
    private val http = Net.client(15, 30)
    private val active = java.util.concurrent.atomic.AtomicInteger(0)
    private val notifId = 4242

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id")
        if (id == null) { stopSelf(); return START_NOT_STICKY }
        Recorder.ensureChannel(this)
        val n: Notification = NotificationCompat.Builder(this, Recorder.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Nabd TV").setContentText(S(R.string.a_1501fee6)).setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(notifId, n)
        } catch (e: Exception) {
            Recorder.markFailed(this, id); stopSelf(); return START_NOT_STICKY
        }
        active.incrementAndGet()
        Thread {
            try { record(id) } finally {
                if (active.decrementAndGet() <= 0) {
                    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
                    stopSelf()
                }
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun upd(store: Store, id: String, f: (Recording) -> Recording) =
        synchronized(Recorder.lock) { store.saveRecordings(store.recordings().map { if (it.id == id) f(it) else it }) }

    private fun cancelled(store: Store, id: String) =
        store.recordings().firstOrNull { it.id == id }?.status == RecStatus.Cancelled

    private fun record(id: String) {
        val store = Store(this)
        val rec = store.recordings().firstOrNull { it.id == id } ?: return
        if (rec.status == RecStatus.Cancelled) return
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir, "NabdTV").apply { mkdirs() }
        val safe = rec.title.replace(Regex("""[^\p{L}\p{N} _-]"""), "").trim().take(40).ifEmpty { "rec" }
        val out = File(dir, "${safe}_${rec.start}.ts")
        upd(store, id) { it.copy(status = RecStatus.Recording, file = out.absolutePath) }
        try {
            if (rec.channelUrl.contains(".m3u8")) recordHls(store, rec, out) else recordRaw(store, rec, out)
            val ok = out.exists() && out.length() > 200_000
            upd(store, id) { if (it.status == RecStatus.Cancelled) it else it.copy(status = if (ok) RecStatus.Done else RecStatus.Failed) }
        } catch (e: Exception) {
            upd(store, id) { it.copy(status = if (it.status == RecStatus.Cancelled) it.status else RecStatus.Failed) }
        }
    }

    private fun recordRaw(store: Store, rec: Recording, out: File) {
        val req = Request.Builder().url(rec.channelUrl).header("User-Agent", httpUa).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val buf = ByteArray(64 * 1024)
            var lastCheck = 0L
            FileOutputStream(out, true).use { o ->
                r.body!!.byteStream().use { i ->
                    while (System.currentTimeMillis() < rec.stop) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        val now = System.currentTimeMillis()
                        if (now - lastCheck > 3000) { lastCheck = now; if (cancelled(store, rec.id)) break }
                    }
                }
            }
        }
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", httpUa).build()
        http.newCall(req).execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); return r.body!!.string() }
    }

    private fun resolve(base: String, ref: String): String =
        if (ref.startsWith("http")) ref else URI(base).resolve(ref).toString()

    private fun recordHls(store: Store, rec: Recording, out: File) {
        var playlist = rec.channelUrl
        val seen = LinkedHashSet<String>()
        var failures = 0
        FileOutputStream(out, true).use { o ->
            while (System.currentTimeMillis() < rec.stop && !cancelled(store, rec.id)) {
                val lines = try { get(playlist).lines() } catch (e: Exception) {
                    if (++failures > 5) throw e
                    Thread.sleep(2000); continue
                }
                val v = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
                if (v >= 0) {
                    val next = lines.drop(v + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: error("empty master")
                    playlist = resolve(playlist, next.trim()); continue
                }
                var fetched = false
                for (s in lines.filter { it.isNotBlank() && !it.startsWith("#") }) {
                    val u = resolve(playlist, s.trim())
                    if (!seen.add(u)) continue
                    val req = Request.Builder().url(u).header("User-Agent", httpUa).build()
                    http.newCall(req).execute().use { r -> if (r.isSuccessful) o.write(r.body!!.bytes()) }
                    fetched = true
                    if (System.currentTimeMillis() >= rec.stop) break
                }
                if (seen.size > 600) seen.iterator().let { it.next(); it.remove() }
                Thread.sleep(if (fetched) 1500 else 2000)
            }
        }
    }
}

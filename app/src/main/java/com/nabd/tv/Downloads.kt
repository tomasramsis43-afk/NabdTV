package com.nabd.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

enum class DlStatus { Queued, Downloading, Done, Failed, Cancelled }

data class DownloadItem(
    val id: String,
    val title: String,
    val url: String,
    val poster: String?,
    val status: DlStatus,
    val bytes: Long = 0L,
    val total: Long = 0L,
    val file: String? = null,
    val added: Long = 0L
)

/** Pure rules (no Android), so they can be unit-tested. */
object DownloadRules {
    private val VIDEO_EXT = setOf("mp4", "mkv", "m4v", "webm", "avi", "mov")

    /** Only movies / episodes (plain files) can be saved; live channels and HLS/DASH streams cannot. */
    fun isDownloadable(url: String): Boolean {
        val u = url.trim().lowercase()
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false
        val path = u.substringBefore('?').substringBefore('#')
        if ("/live/" in path || path.endsWith(".ts") || ".m3u8" in path || path.endsWith(".mpd")) return false
        return "/movie/" in path || "/series/" in path || path.substringAfterLast('.', "") in VIDEO_EXT
    }

    fun extFor(url: String): String {
        val e = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
        return if (e in VIDEO_EXT) e else "mp4"
    }

    fun idFor(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    fun percent(bytes: Long, total: Long): Int = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0

    fun sizeText(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        else -> "%d MB".format(bytes / (1024 * 1024))
    }
}

/** Queue + persistent state of downloads. The UI observes [items]; [DownloadService] does the work. */
object Downloads {
    const val CHANNEL_ID = "nabd_dl"
    private val lock = Any()
    val items = MutableStateFlow<List<DownloadItem>>(emptyList())

    /** Called once at app start. A download that was running when the app died is marked Failed (a retry resumes it). */
    fun init(ctx: Context) = synchronized(lock) {
        val store = Store(ctx)
        val fixed = store.downloads().map {
            if (it.status == DlStatus.Queued || it.status == DlStatus.Downloading) it.copy(status = DlStatus.Failed) else it
        }
        store.saveDownloads(fixed)
        items.value = fixed
    }

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir, "NabdTV/downloads").apply { mkdirs() }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, S(R.string.dl_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun update(ctx: Context, id: String, persist: Boolean = true, f: (DownloadItem) -> DownloadItem) = synchronized(lock) {
        val list = items.value.map { if (it.id == id) f(it) else it }
        items.value = list
        if (persist) Store(ctx).saveDownloads(list)
    }

    private fun replaceAll(ctx: Context, list: List<DownloadItem>) = synchronized(lock) {
        items.value = list
        Store(ctx).saveDownloads(list)
    }

    fun nextQueued(): DownloadItem? = items.value.filter { it.status == DlStatus.Queued }.minByOrNull { it.added }

    /** True when the item was cancelled or removed while it was downloading. */
    fun isCancelled(id: String): Boolean {
        val s = items.value.firstOrNull { it.id == id }?.status
        return s == null || s == DlStatus.Cancelled
    }

    /** Queues [url]. Returns false when it cannot be downloaded (live channel, stream, bad link). */
    fun enqueue(ctx: Context, title: String, url: String, poster: String?): Boolean {
        if (!DownloadRules.isDownloadable(url)) return false
        val id = DownloadRules.idFor(url)
        val queued = synchronized(lock) {
            val cur = items.value.firstOrNull { it.id == id }
            val busy = cur != null && (cur.status == DlStatus.Queued || cur.status == DlStatus.Downloading)
            val done = cur != null && cur.status == DlStatus.Done && cur.file?.let { File(it).exists() } == true
            if (busy || done) false else {
                val fresh = DownloadItem(id, title, url, poster, DlStatus.Queued, cur?.bytes ?: 0L, cur?.total ?: 0L, null, System.currentTimeMillis())
                replaceAll(ctx, listOf(fresh) + items.value.filter { it.id != id })
                true
            }
        }
        if (queued) start(ctx)
        return true
    }

    fun retry(ctx: Context, d: DownloadItem) { enqueue(ctx, d.title, d.url, d.poster) }

    private fun start(ctx: Context) {
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java)) }.onFailure {
            val list = items.value.map { if (it.status == DlStatus.Queued) it.copy(status = DlStatus.Failed) else it }
            replaceAll(ctx, list)
        }
    }

    fun cancel(ctx: Context, id: String) {
        update(ctx, id) { if (it.status == DlStatus.Queued || it.status == DlStatus.Downloading) it.copy(status = DlStatus.Cancelled) else it }
        runCatching { File(dir(ctx), "$id.part").delete() }
    }

    /** Removes the entry and its file from the device. */
    fun delete(ctx: Context, id: String) {
        val d = items.value.firstOrNull { it.id == id }
        replaceAll(ctx, items.value.filter { it.id != id })
        runCatching { d?.file?.let { File(it).delete() } }
        runCatching { File(dir(ctx), "$id.part").delete() }
    }
}

/** Foreground service (dataSync) that downloads the queue one file at a time and resumes partial files. */
class DownloadService : Service() {
    private val http = Net.client(15, 60)
    private val notifId = 4343
    private val running = AtomicBoolean(false)
    @Volatile private var timedOut = false

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android 15 limits dataSync services to about 6 hours a day; stop at once, partial files are kept for a retry. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Downloads.ensureChannel(this)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(notifId, notif(S(R.string.dl_notif_title), null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(notifId, notif(S(R.string.dl_notif_title), null))
        } catch (e: Exception) {
            Downloads.items.value.filter { it.status == DlStatus.Queued }.forEach { q -> Downloads.update(this, q.id) { it.copy(status = DlStatus.Failed) } }
            stopSelf(); return START_NOT_STICKY
        }
        if (running.compareAndSet(false, true)) {
            Thread {
                try {
                    while (!timedOut) {
                        val next = Downloads.nextQueued() ?: break
                        download(next)
                    }
                } finally {
                    running.set(false)
                    if (!timedOut && Downloads.nextQueued() != null) {
                        // something was queued while the worker was finishing: run again
                        runCatching { ContextCompat.startForegroundService(this, Intent(this, DownloadService::class.java)) }
                    } else {
                        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
                        stopSelf()
                    }
                }
            }.start()
        }
        return START_NOT_STICKY
    }

    private fun notif(title: String, pct: Int?): Notification =
        NotificationCompat.Builder(this, Downloads.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setOngoing(true).setOnlyAlertOnce(true)
            .apply { if (pct != null) setProgress(100, pct, false) else setProgress(0, 0, true) }
            .build()

    private fun download(item: DownloadItem) {
        val dir = Downloads.dir(this)
        val part = File(dir, item.id + ".part")
        val target = File(dir, item.id + "." + DownloadRules.extFor(item.url))
        Downloads.update(this, item.id) { it.copy(status = DlStatus.Downloading) }
        var cancelled = false
        var interrupted = false
        try {
            var have = if (part.exists()) part.length() else 0L
            val rb = Request.Builder().url(item.url).header("User-Agent", httpUa)
            if (have > 0) rb.header("Range", "bytes=$have-")
            http.newCall(rb.build()).execute().use { r ->
                if (r.code == 416 && have > 0) return@use           // the file on disk is already the whole thing
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val ct = r.header("Content-Type").orEmpty().lowercase()
                if (ct.startsWith("text/") || "mpegurl" in ct || "json" in ct) error("not a video")
                val body = r.body ?: error("empty response")
                val resumed = r.code == 206
                if (!resumed) have = 0L
                val len = body.contentLength()
                val total = when {
                    resumed -> r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: if (len > 0) have + len else 0L
                    else -> if (len > 0) len else 0L
                }
                if (total > 0 && total - have > dir.usableSpace - 50L * 1024 * 1024) error("no space")
                val buf = ByteArray(64 * 1024)
                var written = have
                var lastUi = 0L
                var lastSave = 0L
                FileOutputStream(part, resumed).use { o ->
                    body.byteStream().use { i ->
                        while (true) {
                            if (timedOut) { interrupted = true; break }
                            val n = i.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            written += n
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 500) {
                                lastUi = now
                                if (Downloads.isCancelled(item.id)) { cancelled = true; break }
                                val save = now - lastSave > 3000
                                if (save) lastSave = now
                                val w = written
                                Downloads.update(this, item.id, save) { it.copy(bytes = w, total = total) }
                                runCatching {
                                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                                        .notify(notifId, notif(item.title, if (total > 0) DownloadRules.percent(w, total) else null))
                                }
                            }
                        }
                    }
                }
                if (!cancelled && !interrupted && total > 0 && written < total) error("connection dropped")
            }
            if (cancelled) { part.delete(); return }
            if (interrupted) { Downloads.update(this, item.id) { it.copy(status = DlStatus.Failed) }; return }
            if (!part.exists() || part.length() < 200_000L) error("file too small")
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) error("could not save the file")
            val size = target.length()
            Downloads.update(this, item.id) { it.copy(status = DlStatus.Done, file = target.absolutePath, bytes = size, total = size) }
        } catch (e: Exception) {
            if (Downloads.isCancelled(item.id)) { part.delete(); return }
            Downloads.update(this, item.id) { it.copy(status = DlStatus.Failed) }
        }
    }
}

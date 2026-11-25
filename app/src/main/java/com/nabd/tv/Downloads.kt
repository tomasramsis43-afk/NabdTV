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
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

    /** Parallel download: connections used for one file, size of one block, and the smallest file worth splitting. */
    const val CONNECTIONS = 4
    const val BLOCK_BYTES = 8L * 1024 * 1024
    const val MIN_PARALLEL_BYTES = 16L * 1024 * 1024

    fun blockCount(total: Long, block: Long = BLOCK_BYTES): Int = if (total <= 0) 0 else ((total + block - 1) / block).toInt()

    /** First byte of block [i]. */
    fun blockStart(i: Int, block: Long = BLOCK_BYTES): Long = i * block

    /** Last byte (inclusive) of block [i]; the final block is shorter. */
    fun blockEnd(i: Int, total: Long, block: Long = BLOCK_BYTES): Long = minOf(total, (i + 1) * block) - 1

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
        runCatching { File(dir(ctx), "$id.seg").delete() }
    }

    /** Removes the entry and its file from the device. */
    fun delete(ctx: Context, id: String) {
        val d = items.value.firstOrNull { it.id == id }
        replaceAll(ctx, items.value.filter { it.id != id })
        runCatching { d?.file?.let { File(it).delete() } }
        runCatching { File(dir(ctx), "$id.part").delete() }
        runCatching { File(dir(ctx), "$id.seg").delete() }
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

    private enum class Outcome { Ok, Cancelled, Interrupted }

    /** One slice of the file. [done] counts bytes already on disk from [start]; only one worker owns a block at a time. */
    private class Block(val start: Long, val end: Long) {
        @Volatile var done = 0L
        val len get() = end - start + 1
        val finished get() = done >= len
    }

    private fun download(item: DownloadItem) {
        val dir = Downloads.dir(this)
        val part = File(dir, item.id + ".part")
        val seg = File(dir, item.id + ".seg")       // progress of each block of a parallel download (lets a retry resume)
        val target = File(dir, item.id + "." + DownloadRules.extFor(item.url))
        Downloads.update(this, item.id) { it.copy(status = DlStatus.Downloading) }
        try {
            // A leftover .part without .seg comes from the old single-connection mode: keep resuming it that way.
            val total = if (part.exists() && !seg.exists()) null else probeRange(item.url)
            val outcome = if (total != null && total >= DownloadRules.MIN_PARALLEL_BYTES) {
                downloadParallel(item, part, seg, total)
            } else {
                // The server does not do ranges (or the file is small): a preallocated parallel .part must not be resumed as-is.
                if (seg.exists()) { seg.delete(); part.delete() }
                downloadSingle(item, part)
            }
            when (outcome) {
                Outcome.Cancelled -> { part.delete(); seg.delete(); return }
                Outcome.Interrupted -> { Downloads.update(this, item.id) { it.copy(status = DlStatus.Failed) }; return }
                Outcome.Ok -> {}
            }
            if (!part.exists() || part.length() < 200_000L) error("file too small")
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) error("could not save the file")
            seg.delete()
            val size = target.length()
            Downloads.update(this, item.id) { it.copy(status = DlStatus.Done, file = target.absolutePath, bytes = size, total = size) }
        } catch (e: Exception) {
            if (Downloads.isCancelled(item.id)) { part.delete(); seg.delete(); return }
            Downloads.update(this, item.id) { it.copy(status = DlStatus.Failed) }
        }
    }

    /** Asks for one byte with a Range header. Returns the full size when the server answers 206 (ranges work), else null. */
    private fun probeRange(url: String): Long? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", httpUa).header("Range", "bytes=0-0").build()
        http.newCall(req).execute().use { r ->
            if (r.code != 206) return@use null
            val ct = r.header("Content-Type").orEmpty().lowercase()
            if (ct.startsWith("text/") || "mpegurl" in ct || "json" in ct) return@use null
            r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.takeIf { it > 0 }
        }
    }.getOrNull()

    private fun loadSeg(f: File, total: Long, count: Int): LongArray? = runCatching {
        val p = f.readText().trim().split(',')
        if (p.size != count + 1 || p[0].toLong() != total) null else LongArray(count) { p[it + 1].toLong() }
    }.getOrNull()

    private fun saveSeg(f: File, total: Long, blocks: Array<Block>) {
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(total.toString() + "," + blocks.joinToString(",") { it.done.toString() })
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /**
     * Downloads one file over [DownloadRules.CONNECTIONS] connections. The file is cut into blocks held in a shared queue;
     * each worker takes the next block and writes it at its own offset. If the server refuses the extra connections
     * (connection limit of the account), those workers quietly stop and worker 0 carries on alone, so it degrades to the
     * old single-connection speed instead of failing.
     */
    private fun downloadParallel(item: DownloadItem, part: File, seg: File, total: Long): Outcome {
        val dir = part.parentFile!!
        val count = DownloadRules.blockCount(total)
        val saved = if (part.exists() && part.length() == total) loadSeg(seg, total, count) else null
        val blocks = Array(count) { i ->
            Block(DownloadRules.blockStart(i), DownloadRules.blockEnd(i, total)).also { b ->
                b.done = (saved?.get(i) ?: 0L).coerceIn(0L, b.len)
            }
        }
        val already = blocks.sumOf { it.done }
        if (total - already > dir.usableSpace - 50L * 1024 * 1024 + (if (saved == null && part.exists()) part.length() else 0L)) error("no space")
        if (saved == null) {
            part.delete()
            RandomAccessFile(part, "rw").use { it.setLength(total) }
        }
        saveSeg(seg, total, blocks)

        val queue = ConcurrentLinkedQueue<Block>()
        val written = AtomicLong(already)
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Exception?>(null)

        fun worker(idx: Int) {
            val buf = ByteArray(64 * 1024)
            var fails = 0
            try {
                RandomAccessFile(part, "rw").use { raf ->
                    while (!stop.get()) {
                        val b = queue.poll() ?: break
                        try {
                            fetchBlock(item.url, b, raf, buf, written, stop)
                            fails = 0
                        } catch (e: Exception) {
                            queue.add(b)                       // another worker (or the clean-up pass) finishes it
                            if (stop.get()) break
                            if (idx != 0) return               // extra connection refused / dropped: worker 0 keeps going
                            if (++fails > 3) throw e
                            Thread.sleep(1000L * fails)
                        }
                    }
                }
            } catch (e: Exception) {
                failure.compareAndSet(null, e); stop.set(true)
            }
        }

        queue.addAll(blocks.filter { !it.finished })
        val workers = List(DownloadRules.CONNECTIONS.coerceAtMost(queue.size.coerceAtLeast(1))) { idx ->
            Thread {
                if (idx > 0) runCatching { Thread.sleep(250L * idx) }   // stagger the extra connections
                worker(idx)
            }.also { it.start() }
        }

        var cancelled = false
        var interrupted = false
        var lastSave = 0L
        while (workers.any { it.isAlive }) {
            runCatching { Thread.sleep(500) }
            if (Downloads.isCancelled(item.id)) { cancelled = true; stop.set(true) }
            if (timedOut) { interrupted = true; stop.set(true) }
            val now = System.currentTimeMillis()
            val save = now - lastSave > 3000
            if (save) { lastSave = now; saveSeg(seg, total, blocks) }
            val w = written.get()
            Downloads.update(this, item.id, save) { it.copy(bytes = w, total = total) }
            runCatching {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(notifId, notif(item.title, DownloadRules.percent(w, total)))
            }
        }
        workers.forEach { it.join() }

        // A refused extra worker may have put its block back after worker 0 had already finished: sweep what is left.
        while (!stop.get() && blocks.any { !it.finished }) {
            queue.clear(); queue.addAll(blocks.filter { !it.finished })
            val before = written.get()
            worker(0)
            if (written.get() == before && failure.get() == null) failure.compareAndSet(null, java.io.IOException("no progress"))
            if (Downloads.isCancelled(item.id)) { cancelled = true; stop.set(true) }
            if (timedOut) { interrupted = true; stop.set(true) }
        }
        saveSeg(seg, total, blocks)
        if (cancelled) return Outcome.Cancelled
        if (interrupted) return Outcome.Interrupted
        failure.get()?.let { throw it }
        if (blocks.any { !it.finished }) error("connection dropped")
        return Outcome.Ok
    }

    /** Reads one block over its own ranged request, appending at the block's offset. Throws if the server does not answer 206. */
    private fun fetchBlock(url: String, b: Block, raf: RandomAccessFile, buf: ByteArray, written: AtomicLong, stop: AtomicBoolean) {
        val from = b.start + b.done
        val req = Request.Builder().url(url).header("User-Agent", httpUa).header("Range", "bytes=$from-${b.end}").build()
        http.newCall(req).execute().use { r ->
            if (r.code != 206) error("HTTP ${r.code}")
            val body = r.body ?: error("empty response")
            raf.seek(from)
            body.byteStream().use { i ->
                while (!stop.get()) {
                    val want = minOf(buf.size.toLong(), b.len - b.done).toInt()
                    if (want <= 0) break
                    val n = i.read(buf, 0, want)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    b.done += n
                    written.addAndGet(n.toLong())
                }
            }
        }
        if (!stop.get() && !b.finished) error("connection dropped")
    }

    /** The original single-connection download (small files, servers without Range support, old partial files). */
    private fun downloadSingle(item: DownloadItem, part: File): Outcome {
        val dir = part.parentFile!!
        var cancelled = false
        var interrupted = false
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
        return if (cancelled) Outcome.Cancelled else if (interrupted) Outcome.Interrupted else Outcome.Ok
    }
}

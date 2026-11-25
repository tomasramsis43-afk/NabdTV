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
    val added: Long = 0L,
    val speed: Long = 0L,   // bytes per second right now (live only, not saved)
    val conns: Int = 0      // connections currently pulling data (live only, not saved)
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

    fun speedText(bytesPerSec: Long): String = when {
        bytesPerSec >= 1024L * 1024 -> "%.1f MB/s".format(bytesPerSec / (1024.0 * 1024))
        else -> "%d KB/s".format(bytesPerSec / 1024)
    }

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

    /** Speed over the last ~2 seconds. */
    private class SpeedMeter(start: Long) {
        private var t = System.currentTimeMillis()
        private var b = start
        var bps = 0L
            private set
        fun sample(bytes: Long, now: Long): Long {
            val dt = now - t
            if (dt >= 2000) { bps = ((bytes - b) * 1000 / dt).coerceAtLeast(0L); t = now; b = bytes }
            return bps
        }
    }

    private enum class Outcome { Ok, Cancelled, Interrupted }

    /** One slice of the file. [done] counts bytes already on disk from [start]; only one worker owns a block at a time. */
    private class Block(val start: Long, val end: Long) {
        @Volatile var done = 0L
        val len get() = end - start + 1
        val finished get() = done >= len
    }

    /** Bytes the last parallel attempt managed to write; if it fails with almost nothing, we retry with one plain connection. */
    @Volatile private var lastParallelBytes = 0L

    private fun looksLikeNotAVideo(r: okhttp3.Response): Boolean {
        val ct = r.header("Content-Type").orEmpty().lowercase()
        return ct.startsWith("text/") || "mpegurl" in ct || "json" in ct
    }

    private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()

    /** A host that refused extra connections (or dropped us when we opened them) is downloaded over one connection for a day. */
    private fun singleOnly(url: String): Boolean {
        val t = getSharedPreferences("dl_prefs", MODE_PRIVATE).getLong("single_" + hostOf(url), 0L)
        return t > 0 && System.currentTimeMillis() - t < 24L * 3600 * 1000
    }

    private fun markSingleOnly(url: String) {
        getSharedPreferences("dl_prefs", MODE_PRIVATE).edit().putLong("single_" + hostOf(url), System.currentTimeMillis()).apply()
    }

    private fun download(item: DownloadItem) {
        val dir = Downloads.dir(this)
        val part = File(dir, item.id + ".part")
        val seg = File(dir, item.id + ".seg")       // progress of each block of a parallel download (lets a retry resume)
        val target = File(dir, item.id + "." + DownloadRules.extFor(item.url))
        Downloads.update(this, item.id) { it.copy(status = DlStatus.Downloading) }
        var first: okhttp3.Response? = null
        try {
            val saved = if (seg.exists()) readSeg(seg, part) else null
            if (seg.exists() && saved == null) { seg.delete(); part.delete() }   // unreadable progress file: start clean
            val outcome: Outcome
            if (saved != null) {
                outcome = downloadBlocks(item, part, seg, saved.first, saved.second, null)
            } else if (part.exists() && part.length() > 0) {
                outcome = downloadSingle(item, part, null)       // partial file from the old single-connection mode
            } else {
                part.delete()
                // The first request is both the probe and the first block, so no extra connection is ever spent on probing.
                val r = http.newCall(
                    Request.Builder().url(item.url).header("User-Agent", httpUa)
                        .header("Range", "bytes=0-${DownloadRules.BLOCK_BYTES - 1}").build()
                ).execute()
                first = r
                val total = if (r.code == 206 && !looksLikeNotAVideo(r))
                    r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.takeIf { it > 0 } else null
                if (total != null) {
                    first = null
                    outcome = try {
                        downloadBlocks(item, part, seg, total, null, r)
                    } catch (e: Exception) {
                        if (Downloads.isCancelled(item.id) || lastParallelBytes >= 1024L * 1024) throw e
                        // Parallel mode failed before getting anywhere: remember it and use the old, plain way.
                        markSingleOnly(item.url)
                        part.delete(); seg.delete()
                        downloadSingle(item, part, null)
                    }
                } else if (r.code == 206) {
                    r.close(); first = null
                    outcome = downloadSingle(item, part, null)
                } else {
                    first = null
                    outcome = downloadSingle(item, part, r)      // 200: the whole body is on this response
                }
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
        } finally {
            runCatching { first?.close() }
        }
    }

    private fun readSeg(f: File, part: File): Pair<Long, LongArray>? = runCatching {
        val p = f.readText().trim().split(',')
        val total = p[0].toLong()
        val count = DownloadRules.blockCount(total)
        if (p.size != count + 1 || !part.exists() || part.length() != total) null
        else total to LongArray(count) { p[it + 1].toLong() }
    }.getOrNull()

    private fun saveSeg(f: File, total: Long, blocks: Array<Block>) {
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(total.toString() + "," + blocks.joinToString(",") { it.done.toString() })
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /**
     * Downloads the file block by block. Worker 0 behaves exactly like the old single connection (it starts at once, on the
     * response already opened). Extra connections are added one at a time, only after worker 0 has proved the stream flows,
     * and are all dropped for good the moment the server refuses one or drops worker 0 when one opens.
     */
    private fun downloadBlocks(item: DownloadItem, part: File, seg: File, total: Long, savedDone: LongArray?, first: okhttp3.Response?): Outcome {
        lastParallelBytes = 0L
        val dir = part.parentFile!!
        val count = DownloadRules.blockCount(total)
        val blocks = Array(count) { i ->
            Block(DownloadRules.blockStart(i), DownloadRules.blockEnd(i, total)).also { b ->
                b.done = (savedDone?.get(i) ?: 0L).coerceIn(0L, b.len)
            }
        }
        val already = blocks.sumOf { it.done }
        try {
            if (savedDone == null) {
                if (total > dir.usableSpace - 50L * 1024 * 1024) error("no space")
                part.delete()
                RandomAccessFile(part, "rw").use { it.setLength(total) }
            } else if (total - already > dir.usableSpace - 50L * 1024 * 1024) error("no space")
        } catch (e: Exception) { first?.close(); throw e }
        saveSeg(seg, total, blocks)

        val queue = ConcurrentLinkedQueue<Block>()
        val written = AtomicLong(already)
        val stop = AtomicBoolean(false)          // whole download: cancel / timeout / failure
        val extraStop = AtomicBoolean(false)     // only the extra connections
        val refused = AtomicBoolean(false)       // an extra connection was refused or cost us worker 0's stream
        val extrasRunning = AtomicBoolean(false)
        val failure = AtomicReference<Exception?>(null)

        fun worker(idx: Int, pre: okhttp3.Response?, firstBlock: Block?) {
            val buf = ByteArray(64 * 1024)
            var fails = 0
            var preResp = pre
            var carry = firstBlock
            fun halted() = stop.get() || (idx != 0 && extraStop.get())
            try {
                RandomAccessFile(part, "rw").use { raf ->
                    while (!halted()) {
                        val b = carry ?: queue.poll() ?: break
                        carry = null
                        try {
                            fetchBlock(item.url, b, raf, buf, written, ::halted, preResp)
                            preResp = null
                            fails = 0
                            if (!b.finished) queue.add(b)
                        } catch (e: Exception) {
                            preResp = null
                            queue.add(b)
                            if (stop.get()) break
                            if (idx != 0) { refused.set(true); return }      // extra connection refused: worker 0 carries on alone
                            if (extrasRunning.get()) { refused.set(true); extraStop.set(true) }
                            if (++fails > 3) throw e
                            Thread.sleep(1000L * fails)
                        }
                    }
                }
            } catch (e: Exception) {
                failure.compareAndSet(null, e); stop.set(true)
            } finally {
                runCatching { preResp?.close() }
            }
        }

        val block0 = if (first != null && !blocks[0].finished) blocks[0] else null
        queue.addAll(blocks.filter { !it.finished && it !== block0 })
        val workers = mutableListOf<Thread>()
        workers += Thread { worker(0, if (block0 != null) first else null, block0) }.also { it.start() }
        if (block0 == null) runCatching { first?.close() }

        val parallelOk = total >= DownloadRules.MIN_PARALLEL_BYTES && count > 1 && !singleOnly(item.url)
        var extrasStarted = 0
        var nextExtraAt = 0L
        var cancelled = false
        var interrupted = false
        var lastSave = 0L
        var lastBytes = already
        var lastMoveAt = System.currentTimeMillis()
        val meter = SpeedMeter(already)
        while (workers.any { it.isAlive }) {
            runCatching { Thread.sleep(500) }
            val now = System.currentTimeMillis()
            if (Downloads.isCancelled(item.id)) { cancelled = true; stop.set(true) }
            if (timedOut) { interrupted = true; stop.set(true) }
            val w = written.get()
            lastParallelBytes = w - already
            if (w != lastBytes) { lastBytes = w; lastMoveAt = now }

            // Ramp up: one extra connection at a time, only once the first one has moved at least 1 MB.
            if (parallelOk && !stop.get() && !extraStop.get() && extrasStarted < DownloadRules.CONNECTIONS - 1 &&
                w - already >= 1024L * 1024 && now >= nextExtraAt && !queue.isEmpty()) {
                extrasStarted++
                extrasRunning.set(true)
                nextExtraAt = now + 1500
                val idx = extrasStarted
                workers += Thread { worker(idx, null, null) }.also { it.start() }
            }
            // The server pushed back (refused an extra or dropped worker 0), or everything stalled with extras open.
            if (extrasRunning.get() && !extraStop.get() && (refused.get() || now - lastMoveAt > 30_000)) {
                extraStop.set(true); refused.set(true)
            }
            if (refused.get()) markSingleOnly(item.url)

            val save = now - lastSave > 3000
            if (save) { lastSave = now; saveSeg(seg, total, blocks) }
            val bps = meter.sample(w, now)
            val live = workers.count { it.isAlive }
            Downloads.update(this, item.id, save) { it.copy(bytes = w, total = total, speed = bps, conns = live) }
            runCatching {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(notifId, notif(item.title, DownloadRules.percent(w, total)))
            }
        }
        workers.forEach { it.join() }

        // A dropped extra may have put its block back after worker 0 had already finished: sweep what is left, one connection.
        while (!stop.get() && blocks.any { !it.finished }) {
            queue.clear(); queue.addAll(blocks.filter { !it.finished })
            val before = written.get()
            extraStop.set(true)
            worker(0, null, null)
            if (written.get() == before && failure.get() == null) failure.compareAndSet(null, java.io.IOException("no progress"))
            if (Downloads.isCancelled(item.id)) { cancelled = true; stop.set(true) }
            if (timedOut) { interrupted = true; stop.set(true) }
        }
        lastParallelBytes = written.get() - already
        saveSeg(seg, total, blocks)
        if (cancelled) return Outcome.Cancelled
        if (interrupted) return Outcome.Interrupted
        failure.get()?.let { throw it }
        if (blocks.any { !it.finished }) error("connection dropped")
        return Outcome.Ok
    }

    /**
     * Reads one block over a ranged request (or over [pre], an already open response for exactly this block), writing at the
     * block's offset. Throws unless the server answers 206. Returns normally if [halted] cut it short.
     */
    private fun fetchBlock(
        url: String, b: Block, raf: RandomAccessFile, buf: ByteArray, written: AtomicLong,
        halted: () -> Boolean, pre: okhttp3.Response?
    ) {
        val from = b.start + b.done
        val resp = pre ?: http.newCall(
            Request.Builder().url(url).header("User-Agent", httpUa).header("Range", "bytes=$from-${b.end}").build()
        ).execute()
        resp.use { r ->
            if (r.code != 206) error("HTTP ${r.code}")
            val body = r.body ?: error("empty response")
            raf.seek(from)
            body.byteStream().use { i ->
                while (!halted()) {
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
        if (!halted() && !b.finished) error("connection dropped")
    }

    /** The original single-connection download. [firstResp] is an already opened 200 response for the whole file, if any. */
    private fun downloadSingle(item: DownloadItem, part: File, firstResp: okhttp3.Response?): Outcome {
        val dir = part.parentFile!!
        var cancelled = false
        var interrupted = false
        var have = if (firstResp == null && part.exists()) part.length() else 0L
        val resp = firstResp ?: run {
            val rb = Request.Builder().url(item.url).header("User-Agent", httpUa)
            if (have > 0) rb.header("Range", "bytes=$have-")
            http.newCall(rb.build()).execute()
        }
        resp.use { r ->
            if (r.code == 416 && have > 0) return@use           // the file on disk is already the whole thing
            if (!r.isSuccessful) error("HTTP ${r.code}")
            if (looksLikeNotAVideo(r)) error("not a video")
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
            val meter = SpeedMeter(have)
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
                            val bps = meter.sample(w, now)
                            Downloads.update(this, item.id, save) { it.copy(bytes = w, total = total, speed = bps, conns = 1) }
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

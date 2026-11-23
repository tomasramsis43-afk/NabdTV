package com.nabd.tv

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** In-app update from the repo's latest GitHub Release (tag v2.0.<build>, asset *.apk). */
object Updater {
    private const val API = "https://api.github.com/repos/tomasramsis43-afk/NabdTV/releases/latest"

    /** [sha256] is the lower-case hex digest GitHub publishes for the asset (null for older releases that have none). */
    data class Release(val tag: String, val build: Int, val apkUrl: String, val notes: String, val sha256: String? = null)

    fun currentBuild(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Returns the newest release if it is newer than the installed build, null if up to date. Throws on network/repo errors. */
    suspend fun check(ctx: Context, http: OkHttpClient): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(API).header("User-Agent", "NabdTV").header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) error(S(R.string.a_4f740e77))
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val o = JSONObject((r.body ?: error("empty response")).string())
            val tag = o.optString("tag_name")
            val build = tag.substringAfterLast('.').toIntOrNull() ?: return@use null
            val assets = o.optJSONArray("assets") ?: return@use null
            var url: String? = null
            var sha: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    url = a.optString("browser_download_url")
                    // GitHub reports e.g. "sha256:ab12…" for every uploaded asset.
                    sha = a.optString("digest").removePrefix("sha256:").lowercase().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
                    break
                }
            }
            // Only ever download over HTTPS.
            if (url == null || !url.startsWith("https://") || build <= currentBuild(ctx)) null else Release(tag, build, url, o.optString("body"), sha)
        }
    }

    /** Downloads the APK into the cache; [onProgress] gets 0..100. */
    suspend fun download(ctx: Context, http: OkHttpClient, rel: Release, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "NabdTV-${rel.build}.apk")
        http.newCall(Request.Builder().url(rel.apkUrl).header("User-Agent", "NabdTV").build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val body = r.body ?: error("empty response")
            val total = body.contentLength()
            var done = 0L
            var last = -1
            val md = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { i ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n); md.update(buf, 0, n); done += n
                        if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; onProgress(p) } }
                    }
                }
            }
            // Refuse a corrupted or tampered download before it can ever reach the installer.
            val expected = rel.sha256
            if (expected == null) { out.delete(); error("no checksum published for this release") }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (got != expected) { out.delete(); error("checksum mismatch") }
        }
        out
    }

    /**
     * Installs through a PackageInstaller session. On Android 12+ an update of an app that this app installed
     * earlier needs no confirmation at all; otherwise [InstallResultReceiver] shows the system prompt / a notification.
     */
    fun installSession(ctx: Context, apk: File): Boolean = try {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { i -> s.openWrite("nabd.apk", 0, apk.length()).use { o -> i.copyTo(o); s.fsync(o) } }
            val pend = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            )
            s.commit(pend.intentSender)
        }
        true
    } catch (e: Exception) { false }

    /** Opens the system installer. Returns false when the user must first allow installs from this app. */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        if (installSession(ctx, apk)) return true
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}

package com.nabd.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Warms the connection (DNS + TLS) and the playlist of a neighbouring channel so that zapping to it starts faster.
 * Only HLS playlists are fetched, a few KB at most, and the connection is closed straight away, so it never holds
 * one of the provider's simultaneous-stream slots the way opening a real stream would.
 */
object Prefetch {
    private val client = Net.client(4, 4)

    fun isHls(url: String) = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    suspend fun warm(url: String) {
        if (!isHls(url)) return
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(url).header("User-Agent", httpUa).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.source()?.request(8L * 1024)
                }
            }
        }
    }
}

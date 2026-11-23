package com.nabd.tv

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One shared HTTP stack. Every client made here reuses the same connection pool and dispatcher (threads), so connections to the
 * same host (TMDB, the provider's API) are reused instead of each feature opening its own, while each keeps its own timeouts.
 */
object Net {
    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder().connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES)).build()
    }

    fun client(connectSec: Long, readSec: Long, redirects: Boolean = true): OkHttpClient =
        base.newBuilder()
            .connectTimeout(connectSec, TimeUnit.SECONDS).readTimeout(readSec, TimeUnit.SECONDS)
            .followRedirects(redirects).followSslRedirects(redirects).build()
}

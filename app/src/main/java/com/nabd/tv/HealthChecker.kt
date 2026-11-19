package com.nabd.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Stream health. Two sources of truth:
 *  - passive: every real playback success/failure updates the record (zero extra connections);
 *  - active: a throttled ranged probe, meant for favorites / the current category, never the whole lineup,
 *    because providers limit simultaneous connections per account.
 */
class HealthChecker(private val repo: Repo, private val store: Store) {
    private val recheckAfterMs = 24 * 3600_000L

    fun state(url: String): Health {
        val r = store.health(url) ?: return Health.Unknown
        if (r.state == Health.Dead && System.currentTimeMillis() - r.checked > recheckAfterMs) return Health.Unknown
        return r.state
    }

    fun isDead(url: String) = state(url) == Health.Dead

    fun markSuccess(url: String, startupMs: Long) {
        store.setHealth(url, HealthRecord(if (startupMs > 4000) Health.Slow else Health.Ok, startupMs, System.currentTimeMillis(), 0))
    }

    fun markFailure(url: String) {
        val old = store.health(url)
        val f = (old?.failures ?: 0) + 1
        store.setHealth(url, HealthRecord(if (f >= 2) Health.Dead else Health.Slow, old?.latencyMs ?: 0, System.currentTimeMillis(), f))
    }

    fun flush() = store.saveHealth()

    /** Throttled active check. Returns the number of dead streams found. */
    suspend fun check(urls: List<String>, concurrency: Int = 3, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {
            val gate = Semaphore(concurrency)
            var done = 0
            var dead = 0
            coroutineScope {
                urls.map { u ->
                    async {
                        gate.withPermit {
                            val r = repo.probeStream(u)
                            synchronized(this@HealthChecker) {
                                store.setHealth(u, r)
                                done++
                                if (r.state == Health.Dead) dead++
                            }
                            onProgress(done, urls.size)
                        }
                    }
                }.awaitAll()
            }
            store.saveHealth()
            dead
        }

    /** Alternative sources for the same channel (same normalized name), healthiest first, dead ones excluded. */
    fun alternatives(current: Channel, all: List<Channel>): List<Channel> {
        val key = Classifier.normalize(current.name)
        if (key.isEmpty()) return emptyList()
        return all.asSequence()
            .filter { it.url != current.url && Classifier.normalize(it.name) == key && state(it.url) != Health.Dead }
            .sortedBy { score(it.url) }
            .take(5).toList()
    }

    /**
     * Lower is better. Combines the health state, how fast the stream started last time and how often it failed,
     * so a source that always starts in 1 s beats one that is merely "not known to be dead".
     * Unknown sources sit between a good Ok and a Slow one, so they still get a chance to be measured.
     */
    fun score(url: String): Long {
        val r = store.health(url) ?: return UNKNOWN_SCORE
        val base = when (state(url)) {
            Health.Ok -> 0L
            Health.Unknown -> UNKNOWN_SCORE
            Health.Slow -> 20_000L
            Health.Dead -> 100_000L
        }
        return base + r.latencyMs.coerceIn(0, 15_000) + r.failures * 3_000L
    }

    private companion object {
        const val UNKNOWN_SCORE = 8_000L
    }
}

package com.nabd.tv

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.abs

/** On-device recommendation engine: interest profile from history + favorites, content-based scoring. */
object Recommender {

    private fun tags(name: String, group: String): Pair<String, String> =
        "g:" + Classifier.genre(name, group).name to "grp:" + group.lowercase()

    private fun profile(history: List<HistoryEntry>, favs: Set<String>, liked: Set<String>, disliked: Set<String>, pool: List<Channel>, now: Long): HashMap<String, Float> {
        val p = HashMap<String, Float>()
        val hourNow = ((now / 3_600_000L) % 24).toInt()
        fun add(k: String, w: Float) { p[k] = (p[k] ?: 0f) + w }
        for (h in history) {
            val ageDays = ((now - h.last) / 86_400_000f).coerceAtLeast(0f)
            var w = exp(-ageDays / 14f) * (1f + ln(1f + h.count))
            if (h.finished) w *= 1.2f
            if (h.progress in 0.01f..0.1f && h.kind != Kind.Live) w *= 0.4f // abandoned quickly = weak signal
            val hourThen = ((h.last / 3_600_000L) % 24).toInt()
            val diff = minOf(abs(hourThen - hourNow), 24 - abs(hourThen - hourNow))
            if (diff <= 2) w *= 1.3f
            val (g, grp) = tags(h.name, h.group)
            add(g, w); add(grp, w * 0.8f)
        }
        if (favs.isNotEmpty()) for (c in pool) if (c.url in favs) {
            val (g, grp) = tags(c.name, c.group)
            add(g, 2f); add(grp, 1.6f)
        }
        // Thumbs up counts like a favourite (a little more); thumbs down pulls the same genre / category down, gently,
        // so one dislike never wipes out a whole genre.
        if (liked.isNotEmpty() || disliked.isNotEmpty()) for (c in pool) {
            val up = c.url in liked; val down = c.url in disliked
            if (!up && !down) continue
            val (g, grp) = tags(c.name, c.group)
            if (up) { add(g, 2.4f); add(grp, 1.9f) } else { add(g, -1.2f); add(grp, -0.9f) }
        }
        return p
    }

    /** Top picks for the user, excluding finished/duplicate items. Empty when there is no signal yet. */
    fun recommend(
        pool: List<Channel>, history: List<HistoryEntry>, favs: Set<String>, limit: Int,
        now: Long = System.currentTimeMillis(),
        liked: Set<String> = emptySet(), disliked: Set<String> = emptySet()
    ): List<Channel> {
        if (history.isEmpty() && favs.isEmpty() && liked.isEmpty()) return emptyList()
        val p = profile(history, favs, liked, disliked, pool, now)
        if (p.isEmpty()) return emptyList()
        val maxV = p.values.max().coerceAtLeast(0.0001f)
        val seen = history.filter { it.finished || it.kind == Kind.Live }.map { it.url }.toSet() + favs + liked + disliked
        val day = now / 86_400_000L
        val scored = ArrayList<Pair<Channel, Float>>(pool.size / 4)
        for (c in pool) {
            if (c.url in seen) continue
            val (g, grp) = tags(c.name, c.group)
            val s = ((p[g] ?: 0f) * 0.6f + (p[grp] ?: 0f) * 0.4f) / maxV
            if (s <= 0f) continue
            val noise = ((c.url.hashCode() xor day.toInt()) and 0xFF) / 255f * 0.08f // daily variety
            scored += c to (s + noise)
        }
        scored.sortByDescending { it.second }
        // diversity: at most 4 per category
        val perGroup = HashMap<String, Int>()
        val out = ArrayList<Channel>(limit)
        for ((c, _) in scored) {
            val n = perGroup.getOrDefault(c.group, 0)
            if (n >= 4) continue
            perGroup[c.group] = n + 1
            out += c
            if (out.size >= limit) break
        }
        return out
    }

    /** "Because you watched X": items from the same category as the latest watched VOD title. */
    fun becauseYouWatched(pool: List<Channel>, history: List<HistoryEntry>, limit: Int): Pair<String, List<Channel>>? {
        val last = history.firstOrNull { it.kind != Kind.Live } ?: return null
        val seen = history.map { it.url }.toSet()
        val items = pool.filter { it.group == last.group && it.url !in seen }.shuffled().take(limit)
        return if (items.isEmpty()) null else last.name to items
    }
}

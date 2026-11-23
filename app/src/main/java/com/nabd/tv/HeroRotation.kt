package com.nabd.tv

/** Timing and ordering rules for the home poster that changes by itself while nobody touches the screen. */
object HeroRotation {
    /** No touch / remote input for this long before the poster starts rotating. */
    const val IDLE_MS = 15_000L
    /** Time each title stays on screen once rotating. */
    const val PERIOD_MS = 10_000L

    /** The item after [cur] (wrapping around); the first item when [cur] is not in the list; null when there is nothing to rotate. */
    fun <T> next(items: List<T>, cur: T?): T? {
        if (items.size < 2) return null
        val i = items.indexOfFirst { it == cur }
        return items[(i + 1) % items.size]
    }

    fun due(now: Long, lastInput: Long, lastRotate: Long): Boolean =
        now - lastInput >= IDLE_MS && now - lastRotate >= PERIOD_MS
}

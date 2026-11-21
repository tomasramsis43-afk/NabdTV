package com.nabd.tv

/** Slows down PIN guessing: after [freeAttempts] wrong codes every further miss locks entry for longer. */
class PinThrottle(private val freeAttempts: Int = 4, private val now: () -> Long = System::currentTimeMillis) {
    private var misses = 0
    private var blockedUntil = 0L

    /** Seconds still to wait before another try (0 = may try now). */
    fun waitSeconds(): Int = ((blockedUntil - now() + 999) / 1000).coerceAtLeast(0).toInt()

    fun allowed(): Boolean = waitSeconds() == 0

    fun recordMiss() {
        misses++
        if (misses > freeAttempts) {
            val step = (misses - freeAttempts).coerceAtMost(6)
            blockedUntil = now() + 30_000L * (1L shl (step - 1))   // 30 s, 60 s, 2 min ... capped at 16 min
        }
    }

    fun recordSuccess() { misses = 0; blockedUntil = 0L }
}

package com.nabd.tv

/** Slows down PIN guessing: after [freeAttempts] wrong codes every further miss locks entry for longer. */
class PinThrottle(
    private val freeAttempts: Int = 4,
    initialMisses: Int = 0,
    initialBlockedUntil: Long = 0L,
    private val onChange: ((misses: Int, blockedUntil: Long) -> Unit)? = null,
    private val now: () -> Long = System::currentTimeMillis
) {
    private var misses = initialMisses
    private var blockedUntil = initialBlockedUntil

    /** Seconds still to wait before another try (0 = may try now). */
    fun waitSeconds(): Int = ((blockedUntil - now() + 999) / 1000).coerceAtLeast(0).toInt()

    fun allowed(): Boolean = waitSeconds() == 0

    fun recordMiss() {
        misses++
        if (misses > freeAttempts) {
            val step = (misses - freeAttempts).coerceAtMost(6)
            blockedUntil = now() + 30_000L * (1L shl (step - 1))   // 30 s, 60 s, 2 min ... capped at 16 min
        }
        onChange?.invoke(misses, blockedUntil)
    }

    fun recordSuccess() { misses = 0; blockedUntil = 0L; onChange?.invoke(0, 0L) }
}

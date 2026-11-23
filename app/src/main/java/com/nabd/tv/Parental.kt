package com.nabd.tv

/** Parental-control policy: maturity ceiling + PIN-locked categories + a short unlock session. */
class Parental(private val store: Store) {
    @Volatile private var unlockedUntil = 0L

    val unlocked: Boolean get() = System.currentTimeMillis() < unlockedUntil

    private val throttle = PinThrottle(
        initialMisses = store.pinMisses, initialBlockedUntil = store.pinBlockedUntil,
        onChange = { m, b -> store.savePinThrottle(m, b) }
    )

    /** Seconds to wait before the PIN can be tried again (0 = no lockout). */
    fun lockoutSeconds(): Int = throttle.waitSeconds()

    fun unlock(pin: String): Boolean {
        if (!throttle.allowed()) return false
        if (!store.checkPin(pin)) { throttle.recordMiss(); return false }
        throttle.recordSuccess()
        unlockedUntil = System.currentTimeMillis() + 15 * 60_000L
        return true
    }

    fun lock() { unlockedUntil = 0L }

    /** Over the allowed maturity level → never shown (unless the session is unlocked). */
    fun hiddenByLevel(c: Channel): Boolean {
        if (unlocked) return false
        return Classifier.maturity(c) > store.parentalLevel
    }

    /** In a PIN-locked category → shown with a lock, PIN required to open. */
    fun needsPin(c: Channel): Boolean = !unlocked && store.hasPin && c.group in store.lockedGroups

    /** Whether the item should appear in lists at all. */
    fun visible(c: Channel): Boolean {
        if (hiddenByLevel(c)) return false
        if (c.group in store.hiddenGroups) return false
        if (store.hideLocked && needsPin(c)) return false
        return true
    }
}

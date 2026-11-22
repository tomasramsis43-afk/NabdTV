package com.nabd.tv

/** Pure rules of the start-up screen (kept free of Android/Compose so they are unit-tested). */
object SplashLogic {
    const val MIN_MS = 1500L          // long enough for the animation to be seen, not flashed
    const val OFFLINE_MIN_MS = 3500L  // offline: leave time to read the note and press retry
    const val STABLE_MS = 400L        // "ready" must hold this long (loading flags flicker between steps)
    const val CAP_MS = 9000L          // never trap the user on this screen

    enum class Stage { Channels, Movies, Series, Home }

    /** May the splash end now? [readyForMs] = how long the app has continuously been ready (0 = not ready). */
    fun canEnter(elapsedMs: Long, readyForMs: Long, minMs: Long = MIN_MS): Boolean =
        elapsedMs >= CAP_MS || (readyForMs >= STABLE_MS && elapsedMs >= minMs)

    /**
     * Real loading stages with their done flag. Movies and series exist only for Xtream subscriptions.
     * A VOD stage is done when its cached list is in memory, or when channels are loaded and VOD loading has settled
     * (an empty provider catalogue must not leave the stage pending forever).
     */
    fun stages(
        hasXtream: Boolean, channelsLoaded: Boolean, vodSettled: Boolean,
        moviesLoaded: Boolean, seriesLoaded: Boolean, homeReady: Boolean
    ): List<Pair<Stage, Boolean>> = buildList {
        add(Stage.Channels to channelsLoaded)
        if (hasXtream) {
            add(Stage.Movies to (moviesLoaded || (channelsLoaded && vodSettled)))
            add(Stage.Series to (seriesLoaded || (channelsLoaded && vodSettled)))
        }
        add(Stage.Home to homeReady)
    }
}

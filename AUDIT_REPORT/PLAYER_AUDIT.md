# Player audit (Media3) - static reading only

Status: Partially Verified (code read). Not Tested: nothing was run on a device or emulator.

## What the code does well (seen in PlayerEngine.kt / PlayerScreen.kt)
- One ExoPlayer is built per (engine epoch, live) and reused for channel changes through setMediaItem; no player per channel.
- The player is released in a DisposableEffect (playWhenReady=false first, then release); listener and retry handler are removed on dispose.
- Errors: bounded retries (2) with back-off for HTTP 5xx, .ts <-> .m3u8 alternate URL tried once, then live failover / VOD failover, then an error screen with retry. One bad channel does not crash the player.
- Network callback retries once when the connection returns while an error is shown.
- Foreground playback service started/stopped with the player; audio focus handled by Media3 (setAudioAttributes handleAudioFocus=true), noisy-audio handling on.

## Minor observations (no change made)
- ERROR_CODE_BEHIND_LIVE_WINDOW re-prepares without a counter; it is the normal Media3 recovery but is unbounded in theory.
- Delayed retry calls player.prepare() inside runCatching; a retry queued before a channel switch just re-prepares the current item (harmless).
- Rapid channel switching, process death, PiP and screen-off behaviour: Not Tested.

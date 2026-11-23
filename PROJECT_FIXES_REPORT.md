# Fixes applied (this session)

All items compiled and passed CI (unit tests, `assembleDebug`, compatibility lint). None was tested on a device.

| Area | Change | Files |
|---|---|---|
| Thread safety | String-set cache and the channel-health map are now `ConcurrentHashMap` (read from background threads) | `Store.kt` |
| Startup | Filtered live/movie/series lists are prepared on `Dispatchers.Default`; the derived lists use them only when the source list identity and `rev` still match, otherwise they compute as before | `AppViewModel.kt` |
| Stale data | Detail info that arrives after another title was opened is ignored | `AppViewModel.kt` |
| Detail stability | Logo lookup uses a year from the title only (no key change after load); no title text shown while the logo is pending (2 s limit); artwork waits up to 6 s on the detail page | `TitleLogoUi.kt`, `DetailScreen.kt`, `AdaptiveArt.kt` |
| Artwork | TMDB posters are textless only; no TVmaze title poster with a TMDB key; cache keys bumped | `Artwork.kt` |
| Navigation | Swipe / left-right on detail moves along the originating list | `AppViewModel.kt`, `DetailScreen.kt` |
| Back key | Back closes player popups and the side panel directly from the key event | `PlayerScreen.kt`, `PlayerSheet.kt` |
| Tests | Added `AdaptiveArtTest` (artwork pick / size helpers) | `app/src/test/.../AdaptiveArtTest.kt` |

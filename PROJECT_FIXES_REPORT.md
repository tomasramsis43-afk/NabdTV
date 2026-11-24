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

## Second cycle
| Area | Change | Files |
|---|---|---|
| Network | One shared HTTP stack (one connection pool / dispatcher), each client keeps its own timeouts | `Net.kt`, `Artwork.kt`, `TitleLogos.kt`, `TmdbCharts.kt`, `Prefetch.kt`, `Recorder.kt`, `Repo.kt`, `AutoUpdate.kt`, `NabdApp.kt` |
| Player | After a playback error, retries once by itself when the connection comes back | `PlayerScreen.kt` |
| Diagnostics | The six silent `catch` blocks note the failure in `Diag`; the last 40 notes are appended to the crash log | `Diag.kt`, `Repo.kt`, `AppViewModel.kt`, `NabdApp.kt` |
| Accessibility | Poster / wide / live cards announce their title to screen readers | `Components.kt` |
| Providers | Every login is remembered (encrypted) and can be switched from Settings > Account; switching downloads that provider's lists again | `Repo.kt`, `Models.kt`, `AppViewModel.kt`, `SettingsScreen.kt`, strings |
| Settings | Pages refresh right after a change | `SettingsScreen.kt` |
| Release | Optimised (R8) APK attached to every release and preferred by the in-app updater | `build.yml`, `Updater.kt` |
| Tests | `EpgMatcherTest`, `PrefetchTest`, `DiagTest`, `SavedSourceTest` | `app/src/test` |

## Third round
| Area | Change | Files |
|---|---|---|
| Process death | After the system kills the app, it returns to the same tab and open title once the lists are loaded | `MainActivity.kt`, `AppViewModel.kt` |
| Providers | Per-provider cache (rows kept as `live@<id>` etc.), one-time migration of the old cache and category-order files, search limited to the active provider, switching shows cached lists at once | `Repo.kt`, `Db.kt`, `AppViewModel.kt` |
| Diagnostics | `Secret` encryption / decryption failures are noted | `Secret.kt` |

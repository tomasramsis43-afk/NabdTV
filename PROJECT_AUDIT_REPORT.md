# NabdTV – Project Audit Report (first cycle)

**Method and limits.** Static audit only: reading and searching the source (about 16,500 lines of Kotlin in `app/src/main`, 17 unit-test files), the manifest, Gradle files and the CI workflows. Nothing was run on a device or emulator, and no profiler or logcat was available. Every statement below is a finding from code reading; nothing is a measured result.

## Findings

### Critical
None found by static reading.

### High
1. **Startup freeze is undiagnosed.** The app can hang after the spinner. The leading hypothesis is recomputation of the large filtered lists (`liveList`, `movieList`, `seriesList`) on the main thread after loading. Mitigation added in this cycle: the lists are prepared on a background thread (`warmLists`) and used only when their inputs still match. Not confirmed on a device. Needs a logcat from the failing device.
2. **Release build (R8) is not exercised by tests.** `build.yml` runs `assembleRelease`, so it compiles, but the minified build has never been run. `proguard-rules.pro` keeps only Media3. A reflection-dependent library (Room, OkHttp, Coil ship their own consumer rules) failing only in release cannot be excluded.

### Medium
3. **Correction (found when starting the fix):** Xtream login, playlist and EPG URLs, the PIN digest, TMDB / OpenSubtitles / Trakt keys are already encrypted with an Android Keystore AES-GCM key (`Secret.kt`), with a one-time migration of old plain values in `Repo.init`. The first draft of this report wrongly called them plain text. Two real edge cases remain (Low): `Secret.enc` silently stores the plain value if encryption throws, and `Secret.dec` returns an empty string if a value cannot be decrypted (for example after the Keystore key is lost), which looks to the app like "no login saved".
4. **`usesCleartextTraffic="true"`** is needed by many IPTV providers. Deliberately left unchanged (owner's decision).
5. **LAN pairing server** (`Pairing.kt`) receives login details over plain HTTP, protected by an 8-character token and a failed-request cap. Acceptable on a home network; the details are readable by anyone on the same LAN who sniffs it.
6. **Six empty `catch (_: Exception) { }` blocks** (`Repo.kt` 127/169/254/503, `AppViewModel.kt` 440/1030). Each is a deliberate best-effort fallback, but a failure leaves no trace. A diagnostics note would make field problems traceable.
7. **Room uses `fallbackToDestructiveMigration()`.** The table is a re-downloadable channel cache, so impact is a re-sync, not user data loss. Watch history and settings are not in it.
8. **Tests cover logic helpers, not the ViewModel/UI flows** (about 800 test lines for about 16,500 source lines). No instrumented tests exist.

### Low
9. Several lazy lists have no `key` (`DetailScreen` people, `GuideScreens` programmes, `Nav` tabs, `PlayerExtras`). Keys were not added to the programme list because provider data can repeat a start time, and a duplicate key crashes Compose.
10. About 26 `!!` uses. Most are `r.body!!` after a successful response or are guarded by an enclosing `try`; none was found on a path with a realistic null.
11. `AppViewModel.kt` (1,444 lines), `PlayerScreen.kt` (1,484) and `Repo.kt` (758) combine many responsibilities. Splitting is a maintainability point, not a defect.
12. Only one `Log.*` call; diagnostics go through `CrashLog`. No password or token logging was found.

### Checked and found sound
- Network calls that block (`execute()`) sit in IO-dispatched code or worker threads; the shared maps in `Repo` are guarded by `synchronized`; `Logos` uses concurrent maps.
- `Store` caches the stale-risk `HashMap`s (string-set cache, health map): made concurrent in this cycle.
- Self-update checks a SHA-256 digest published by GitHub and refuses a download without one; update URLs must be HTTPS.
- No secrets in the repo; the signing key comes only from CI secrets; the debug fallback key is the standard Android one.
- Crash handling: `CrashLog` plus a separate-process `CrashActivity`.
- Stale-while-revalidate cache, EPG parse cache (`epg_cache.bin`), recommendations snapshot, image disk cache, 7-day detail cache.

## Duplicated / unused code
No automated dead-code tool was run (Kotlin `unusedX` lint was not enabled), so no list is given. Not claimed.

## Scores (out of 100, static evidence only)
| Area | Score | Reason |
|---|---|---|
| Stability | 68 | Open startup freeze; several recent crash-class fixes unverified on device |
| Performance | 72 | Good caching; heavy list derivation was on the main thread (mitigated, unverified) |
| Security | 80 | Checksum-verified updates, Keystore-encrypted credentials / keys / PIN, no repo secrets; cleartext allowed by owner decision |
| Code quality | 70 | Sensible structure; three very large files |
| Test coverage | 55 | Unit tests for helpers only |
| Architecture | 68 | State in the ViewModel, Room/Repo split; no DI, large ViewModel |
| UX | 72 | Consistent, recently reworked; none verified by a person on a device after the latest changes |

# Remaining issues (after the third round)

| # | Issue | Why it is open |
|---|---|---|
| 1 | (closed) Startup freeze: the owner reports it is gone on the release build. If it returns, logcat is needed | Reopen only if it happens again |
| 2 | `Secret.enc` still falls back to plain text on error, and `Secret.dec` still returns "" for an unreadable value | Both failures are now noted in `Diag` and the crash log; changing the behaviour itself alters sign-in and needs a device check |
| 3 | `PlayerScreen` is still one large composable (about 1,170 lines, down from 1,500): its local state and the player effects, key handling and action table remain in one function. `AppViewModel` (1,470 to about 450 lines) and `Repo` (833 to about 620 lines) were split | AppViewModel was split into extension-function files (`VmHome`, `VmLoading`, `VmMisc`, `VmProfiles`, `VmDetail`, `VmPlayback`, `VmTools`); Repo into `RepoEpg`, `RepoSources`, `RepoProbe`; PlayerScreen's overlay layers moved to `PlayerEndLayers`, `PlayerStatusLayers`, `PlayerControlLayers`. What is left in PlayerScreen shares about 49 local values, so splitting it further means a state-holder class, and there is no device to catch regressions |
| 4 | Watch history is one JSON string rewritten on each save (capped at 400 entries) | Small cost at this size |
| 5 | (done) the guide (EPG) files are per provider too | |
| 6 | No ViewModel / UI / instrumented tests; the EPG parser cannot run on the JVM (`android.util.Xml`) | Needs a test harness |
| 7 | Cleartext HTTP allowed, `Secret.kt` encryption scheme untouched | Owner decision |
| 8 | Dependency scan done by hand (okhttp, zxing: no known issues; coil, room, work: not confirmed; media3 1.5.1 is several releases behind 1.11.1). Dependabot added for weekly checks. Dead-code scan done (27 unused declarations and 6 unused strings removed); it only finds names used once, so code that is referenced but never reached is not covered | OSV is blocked from the workspace, so the scan was partial |
| 9 | Nothing in any round was run on a device | No device available here |

Done since the last version of this file: restore of the tab and open title after the system kills the app; per-provider cache with a one-time migration of the old cache; `Secret` failures noted in `Diag`.

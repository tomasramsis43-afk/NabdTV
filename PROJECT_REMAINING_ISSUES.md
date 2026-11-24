# Remaining issues (after the third round)

| # | Issue | Why it is open |
|---|---|---|
| 1 | Startup freeze cause unconfirmed. The owner reports the optimised release build is much faster than debug (debug was the build users installed), so the debug build was a likely contributor | Needs logcat if it still happens on the release build |
| 2 | `Secret.enc` still falls back to plain text on error, and `Secret.dec` still returns "" for an unreadable value | Both failures are now noted in `Diag` and the crash log; changing the behaviour itself alters sign-in and needs a device check |
| 3 | `AppViewModel` (about 1,470 lines), `PlayerScreen` (about 1,500 lines, one composable) and `Repo` (about 830 lines, one class) are each a single large unit | Splitting them means introducing state-holder classes, not moving code, and there is no device here to catch regressions. Not done on purpose |
| 4 | Watch history is one JSON string rewritten on each save (capped at 400 entries) | Small cost at this size |
| 5 | A provider's cache is shown at once on switching, but the guide (EPG) of the new provider is re-downloaded | The guide is one shared file; a per-provider guide file is a small follow-up |
| 6 | No ViewModel / UI / instrumented tests; the EPG parser cannot run on the JVM (`android.util.Xml`) | Needs a test harness |
| 7 | Cleartext HTTP allowed, `Secret.kt` encryption scheme untouched | Owner decision |
| 8 | Dependency vulnerability scan and dead-code scan | Not run |
| 9 | Nothing in any round was run on a device | No device available here |

Done since the last version of this file: restore of the tab and open title after the system kills the app; per-provider cache with a one-time migration of the old cache; `Secret` failures noted in `Diag`.

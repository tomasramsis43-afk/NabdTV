# Remaining issues (after the second cycle)

| # | Issue | Why it is open |
|---|---|---|
| 1 | Startup freeze cause unconfirmed; the optimised release build reported much faster by the owner, so the debug build was a likely contributor | Needs logcat if it still happens on release |
| 2 | `Secret.enc` falls back to plain text on error; `Secret.dec` returns "" for an undecryptable value | Changing either alters sign-in behaviour; needs a device check |
| 3 | After the system kills the app, it restarts at Home (lists come from the local cache, Continue watching resumes the video) | No saved screen state; judged acceptable for this app, not changed |
| 4 | Watch history is one JSON string rewritten on each save (capped at 400 entries) | Small cost at this size; no change needed until the cap grows |
| 5 | Switching providers downloads that provider's lists again (the cache holds one at a time) | A cache per provider needs a database change; not done |
| 6 | Large files (`AppViewModel`, `PlayerScreen`, `Repo`) | Splitting is a pure refactor with regression risk and no device to verify; deliberately not done |
| 7 | No ViewModel / UI / instrumented tests; EPG parser untestable on the JVM (uses `android.util.Xml`) | Needs a test harness |
| 8 | Image descriptions on purely decorative images | Left empty on purpose: the title text next to them is announced |
| 9 | Cleartext HTTP allowed, `Secret.kt` untouched | Owner decision |
| 10 | Dependency vulnerability scan, dead-code scan | Not run |
| 11 | Nothing in either cycle was run on a device | No device available here |

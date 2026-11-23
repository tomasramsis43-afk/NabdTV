# Remaining issues

| # | Issue | Why it is open |
|---|---|---|
| 1 | Startup freeze cause unconfirmed | Needs logcat from the failing device |
| 2 | Release (R8) build never run | Needs a device run of the release APK |
| 3 | `Secret.enc` falls back to plain text on error; `Secret.dec` returns "" for an undecryptable value (shows as "no login saved") | Changing either alters sign-in behaviour; needs a device check. (Credentials themselves are already Keystore-encrypted.) |
| 4 | Cleartext HTTP allowed | Owner decision, required by many IPTV providers |
| 5 | Silent catch blocks | Add diagnostics note per block once a logging policy is chosen |
| 6 | No ViewModel / UI tests | Needs a test harness (fake Repo, Compose test rule) |
| 7 | Large files (ViewModel, PlayerScreen, Repo) | Splitting is a refactor with regression risk; not done without device checks |
| 8 | Unkeyed lazy lists | Keys need guaranteed-unique ids |
| 9 | Dependency vulnerability scan, dead-code scan | Not run |

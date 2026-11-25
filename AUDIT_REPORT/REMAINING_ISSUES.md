# Remaining issues
- Whole-String network/Xtream responses (needs larger refactor; risk with 100k+ items).
- Room exportSchema=false (no schema history); add exportSchema + schema dir before the next migration.
- Large files: PlayerScreen (1169), Store (817), SettingsScreen (790).
- No tests for Xtream, EPG parser, repositories, ViewModel flows; no instrumented/UI/TV-focus tests.
- Nothing measured: build, tests, lint, startup, memory, jank, scrolling were not run (no SDK/device in the audit sandbox). Use CI and a real device.
- Cleartext HTTP allowed by default (required for many IPTV providers).
- Not reviewed in depth: Compose recomposition/focus on every screen, RTL layout, dependency CVEs.

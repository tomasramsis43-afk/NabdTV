# NabdTV - Phase 1 (static discovery) + first fixes

Status key: Verified (seen in code) / Not Tested (needs build or device).

## Environment limits
Gradle build, unit tests, Lint, profiling and device tests were NOT run (no Android SDK / Maven access in the audit sandbox). Everything below is static reading. Run CI on this branch to verify.

## Baseline (verified by reading)
- ~19k lines Kotlin, single module `app`, 27 unit-test files, 3 workflows (build, lint, logo-probe).
- No GlobalScope, no runBlocking, no empty catch blocks, 1 lateinit (Lang.appCtx), 27 `!!`.
- Manifest: allowBackup=false, internal components not exported; exported: MainActivity, NabdWidget.
- Cleartext allowed by default (IPTV is often http); app's own services are https-only.
- Release on main requires KEYSTORE_B64 + KEYSTORE_PASS, blocks otherwise.

## Fixed in this branch
1. Db.kt: removed `fallbackToDestructiveMigration()`; added explicit MIGRATION_1_2 (adds `added` column, keeps rows). Not Tested (needs a v1 DB on device).
2. Repo.kt: `xt()!!` -> clear error when Xtream login is missing (2 sites).
3. VmMisc.kt: pairing no longer uses `!!` after null checks.
4. Epg.kt: date parsing without `!!` chain; bad dates still become 0.

## Open findings (not fixed yet)
- Repo.kt: whole cached M3U read with readText() and parsed in memory (large lists -> memory pressure).
- Repo.kt: `body!!` after retry loop; several `r.body!!` on HTTP responses (null body -> NPE).
- Room: exportSchema=false, so no schema history for future migrations.
- Lint workflow runs only on push to main (not on PRs).
- Large files: PlayerScreen 1169, Store 817, SettingsScreen 790 lines.
- No Xtream/EPG-large-file/stress tests; no instrumented/UI tests.
- Player: only one ExoPlayer builder site (good); release path to be reviewed in Phase 7.

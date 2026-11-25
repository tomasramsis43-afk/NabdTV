# NABDTV_PHASE2_REPORT.md — Phase 2 Remediation & Verification

## 1. Review of Phase 1 Edits
- Reviewed all modified files and call sites of Secret.enc/dec.
- Callers: Repo (url/epg/xt + migration), RepoSources (sources), Store (trakt/os/tmdb/pin), VmProfiles (setPin).
- All secret setters now use encOrNull and skip save on failure (no plaintext storage). setPin uses enc (throws) but VmProfiles.setPin now catches SecretException and reports via vm.say + Diag.
- checkPin still returns false when dec fails (keystore loss) — documented; no auto-clear of data.

## 2. Closed Risks
- Secret plaintext fallback: closed for setters (encOrNull).
- Thread-safety Store caches: closed (synchronized storeLock + @Volatile).
- Memory growth (Artwork/TitleLogos/AniSkip/TheIntroDb): partial (caps added: index 5000, mem 1000, caches 500).
- Scrub coverage: extended.
- Pairing token strength: 12 chars / 10 guesses.
- Backup import validation: size + count caps.
- Recording size: 2GB cap.
- Silent catches: Artwork/TitleLogos now Diag.note.

## 3. Files Changed (Phase 2 additions)
- VmProfiles.kt (setPin catch)
- Artwork.kt (mem/index caps)
- TitleLogos.kt (mem/index caps)
- AniSkip.kt (cache caps)
- TheIntroDb.kt (cache caps)
- + NABDTV_SIGNING_MIGRATION.md, NABDTV_PHASE2_REPORT.md

## 4. Tests Executed
- None executed: no Java/Gradle/Android SDK in environment.
- Added tests (Phase 1): ScrubTest, SecretTest. Not run.

## 5. Build Results
- Debug: NOT RUN (no Java/Gradle/Android SDK).
- Release (R8): NOT RUN.
- Environment lacks: java, gradle, android sdk, sdkmanager.

## 6. Memory/Concurrency Verification
- Not runtime-tested. Static reasoning only.

## 7. Remaining Risks
- UI feedback on secret-save failure is indirect (value unchanged; Diag note). Needs explicit user message.
- Artwork/TitleLogos mem/index caps are coarse (clear-all) not true LRU.
- AniSkip/TheIntroDb caches cleared wholesale at threshold.
- R8/ProGuard unverified.
- Startup freeze unverified.
- No device testing.

## 8. Needs Real Device
- All runtime behavior: playback, EPG, pairing, recording, focus/D-pad, offline handling, startup freeze, R8.

## 9. Readiness
- Code-level P0/P1 substantially closed.
- Still "Needs device verification + build verification" before launch claim.
- Provide environment with Java 17 + Android SDK 35 + Gradle 8.9 to run: gradle testDebugUnitTest, assembleDebug, assembleRelease.

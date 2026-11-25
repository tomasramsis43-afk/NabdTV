# NABDTV_PHASE3_VERIFICATION_REPORT.md — Phase 3 Final Verification

## 1. ApplicationId / Signing Verification
- Gradle (`app/build.gradle.kts`): `applicationId = "com.jolo.tv"`; namespace = `com.nabd.tv`.
- Manifest: `${applicationId}.fileprovider` → `com.jolo.tv.fileprovider`.
- Old app: `com.nabd.tv`; new app: `com.jolo.tv` → distinct packages; install side-by-side possible.
- Scenario 1 (new beside old): possible (different package).
- Scenario 2 (new update with same id+cert): possible (standard Android update).
- Scenario 3 (new update with different cert): rejected by Android (INSTALL_FAILED_UPDATE_INCOMPATIBLE) — expected.
- Scenario 4 (old → new direct upgrade): not possible (different package). Documented in NABDTV_SIGNING_MIGRATION.md.

## 2. Certificate Verification
- No APK files present in environment → apksigner not run.
- No secrets found in repo code (grep KEYSTORE_PASS → 0 matches).
- Reports reference secret *names* only, no values.

## 3. Data Migration (Backup/Restore)
- exportBackup/importBackup reviewed. Import validates v in 1..2, size ≤5MB, history ≤400.
- Backup excludes PIN/credentials (by design). Restore merges (union favorites, history by last).
- Corrupt/modified/oversized/large-schema cases handled via runCatching + validation.
- Not runtime-tested (no device).

## 4. Secret.kt Testing
- All callers audited; setters use encOrNull and skip on failure.
- setPin throws on keystore failure; VmProfiles catches.
- checkPin returns false on dec failure (no auto-clear).
- Unit tests added (SecretTest) but not executed (no Gradle).

## 5. Memory Testing
- Caps added; not runtime/profiled (no device/profiler).
- No measured numbers claimed.

## 6. Build Testing
- Environment: no Java, no Gradle, no Android SDK, no sdkmanager.
- `testDebugUnitTest`, `assembleDebug`, `assembleRelease` NOT RUN.
- Commands to run in proper env:
  ```
  ./gradlew testDebugUnitTest
  ./gradlew assembleDebug
  ./gradlew assembleRelease
  ```
- Basic syntax check: brace/paren balance verified on 5 edited files (all balanced).

## 7. Real-Device Testing
- Not performed (no device/emulator). All items in checklist unexecuted.

## 8. Remaining Issues
- UI feedback on secret-save failure is indirect.
- Coarse cache caps (clear-all) not true LRU.
- R8/ProGuard unverified.
- Startup freeze unverified.
- No instrumented/UI tests.

## 9. Launch-Blocking Risks
- Build not verified.
- Device behavior unverified.
- Signing migration requires user communication (old app uninstall + data backup).

## 10. Readiness
- "Needs build + device verification before launch claim."
- Code-level remediation substantially complete.

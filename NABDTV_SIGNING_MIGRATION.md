# NABDTV_SIGNING_MIGRATION.md — Signing Migration Plan

## Context
- Old signing key (pre 19 Nov 2026) was committed to public git history → treated as compromised.
- App id changed to `com.jolo.tv` (namespace stays `com.nabd.tv`).
- Distribution: direct APK via GitHub Releases (not Google Play App Signing).
- In-app updater downloads `jolo-release.apk` and verifies SHA-256 published by GitHub.

## Fingerprints
- Old cert SHA-256: (not available in repo; retrieve from old APK via `keytool -printcert -jarfile old.apk`).
- New cert SHA-256: (from new keystore; run `keytool -list -v -keystore jolo-release.jks`).

## Scenarios
1. **Old install (com.nabd.tv, old key):** cannot be updated by new APK (different package + different signer). User must uninstall old and install new. Data (prefs) not migrated automatically because different package data dir.
2. **New install (com.jolo.tv, new key):** updates work normally as long as the same new key is used for all future builds.
3. **Play Store:** not used; if migrated later, Play App Signing would manage keys and this plan changes (Google holds signing key).

## Constraints (cannot be overridden)
- Android does not allow updating an app with a different signing certificate.
- Cannot revoke/replace an old key retroactively for already-installed apps.
- Old key in git history remains a risk for anyone who installed old builds; mitigation is user communication + new package.

## Migration Steps for Users
1. Backup data via Settings > Backup (exportBackup) — does not include PIN/credentials.
2. Install `jolo-release.apk` (new package com.jolo.tv).
3. Restore backup in new app.
4. Uninstall old "Nabd TV".
5. Re-enter provider logins (not included in backup for security).

## Security Notes
- Never commit keystore or password.
- Keep KEYSTORE_B64/KEYSTORE_PASS/KEYSTORE_ALIAS in GitHub Secrets.
- If new key is lost, no installed copy can be updated (documented in docs/RELEASE.md).
- Old key must be considered compromised; do not reuse.

## Verification
- `keytool -printcert -jarfile app-release.apk` to confirm signer.
- In-app updater verifies SHA-256 digest from GitHub release asset.
- Manual install test: new APK over old package must fail with INSTALL_FAILED_UPDATE_INCOMPATIBLE (expected).

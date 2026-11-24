# Releasing jolo

## 1. One-time: create a NEW signing key
The key used before 19 Nov 2026 (and its password) was committed to the public history of this repo, so it must be treated as compromised and never used again.

```bash
keytool -genkeypair -v -storetype PKCS12 -keystore jolo-release.jks \
  -alias jolo -keyalg RSA -keysize 4096 -validity 10000
# choose a strong password (PKCS12 uses the same one for the store and the key)
base64 -w0 jolo-release.jks > jolo-release.jks.b64      # macOS: base64 -i jolo-release.jks
```
GitHub → Settings → Secrets and variables → Actions → add:
- `KEYSTORE_B64` = content of `jolo-release.jks.b64`
- `KEYSTORE_PASS` = the password
- `KEYSTORE_ALIAS` = `jolo`

Then keep `jolo-release.jks` and the password in two safe places (password manager + offline copy). **If this key is lost, no installed copy can ever be updated.** Never commit it (`*.jks` is git-ignored).

## 2. What changed for installed users
The app id is now `com.jolo.tv` and the key is new, so jolo installs next to the old "Nabd TV" and does not update it. Tell existing users to install jolo and remove the old app. Later updates of jolo work normally because the key stays the same from now on.

## 3. Publishing
Push to `main`. CI runs the unit tests, builds the signed R8 release APK (the build fails if the secrets are missing), and publishes `jolo-release.apk` as the GitHub Release. The in-app updater downloads that asset. Debug builds are only produced for preview branches and are published as pre-releases (never offered by the updater).

## 4. Before the first public release (not done by CI)
Install the release APK on a real TV / phone and check: login, live playback, VOD, recording, update flow, language switch. R8 shrinking can break things that debug builds do not show.

# Nabd TV (Android TV IPTV player)
1. Open this folder in Android Studio (Koala+ / JDK 17) → Sync.
2. Run on an Android TV emulator or device (ADB over network).
3. First launch: enter your M3U URL.

Remote: OK = show info | Up/Down (or CH+/CH-) = zap | Right = favorite | Back = exit player
Home: long-press OK on a channel = favorite.

## v1.1
- Xtream Codes login (server / user / pass) → auto M3U + XMLTV.
- EPG (XMLTV, also auto-detected from `url-tvg`): now/next on cards + player overlay with progress bar.

## Build APK without Android Studio
Push to GitHub → Actions tab → "Build APK" → download artifact `NabdTV-debug-apk`.

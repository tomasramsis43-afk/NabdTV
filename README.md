# Nabd TV v2 (Android TV / Phone IPTV player)

Kotlin + Jetpack Compose + Media3. Everything runs on the device; no middle server.
Architecture: `docs/ARCHITECTURE.md`.

## Run
1. Open in Android Studio (JDK 17) → Sync → run on Android TV / phone.
2. Or push to GitHub → Actions → "Build APK" → download from Releases.
3. First launch: Xtream Codes login or M3U URL.

## Features
- Home: hero, continue watching, recommendations, favourites, on-air-now shelves.
- Live / Movies / Series with posters + metadata (Xtream, optional TMDB).
- Auto EPG: XMLTV matched by tvg-id then fuzzy name; manual override per channel.
- Catch-up (Xtream timeshift / M3U catchup-source) and scheduled / auto recording, reminders.
- Link health: passive (from playback) + throttled active checks; dead links hidden; auto failover to alternative sources.
- Recommendations on-device from watch history and favourites.
- Auto categorization + parental control (maturity level, PIN-locked / hidden categories).
- Player: HLS / DASH / MP4 / TS, hardware decoding with fallback, audio + subtitle tracks, subtitle styling, aspect ratio, speed, next-episode.
- Multi-view (2–4 channels), global search, channel number entry, custom groups, pinned categories.

## Remote
OK = info / pause | Up/Down or CH+/CH- = zap | Right = favourite (live) / +10s | Menu or Info = player options | digits = channel number.
Long-press OK on a channel/category = actions menu.

# jolo – Privacy

_Last updated: 24 November 2026_

jolo is an IPTV / media player that runs entirely on your device. **There is no jolo account, and nothing about you or your viewing is sent to the developer.**

## What is stored, and where
Everything is stored on your device only: your provider login (encrypted with a key held in the Android Keystore), M3U / Xtream / guide links, favourites, watch history, profiles, parental PIN (stored as a salted hash), settings and recordings.
Android backup is switched off for the app, so this data is not copied to Google Drive. Removing the app removes the data.

## What the app sends over the network, and to whom
| Service | When | What is sent |
|---|---|---|
| Your IPTV provider (Xtream / M3U / guide URLs you add) | Always, to load and play content | Your login, as your provider requires |
| TMDB, TVmaze, AniList, TheIntroDB, AniSkip, Wikidata / Wikimedia | To show posters, logos, ratings, episode dates and skip-intro markers | Titles / ids being looked up (TMDB also receives the API key you entered) |
| Trakt | Only if you connect it | Your Trakt account activity, through Trakt's own sign-in |
| OpenSubtitles | Only if you enter your key and account | Your key, account and the title being searched |
| GitHub (api.github.com / releases) | Update checks and downloads | A normal HTTPS request (your IP address is visible to GitHub) |
| Developer's device counter (Cloudflare Worker) | Once on first run and once a week; can be turned off in Settings ("Send an anonymous usage count") | One empty request marked `install` or the ISO week (for example `w:2026-W48`). No identifier, no device model, no account. The counter does not store IP addresses |
| Sentry | Only if **you** enter a Sentry address in Settings | A crash log with credentials removed |

The app contains no advertising and no analytics SDK. The device counter above is the only thing the developer receives, and it is a plain number.

## Local pairing
When you choose "pair with phone", the TV opens a small web server for a few minutes on your local network, protected by a random one-time address, so you can type login details on your phone. It is closed when finished.

## Content
jolo does not provide, host or sell any channels, movies or series. All content comes from the provider you add. You are responsible for having the right to watch it.

## Contact
Open an issue on the project's GitHub page.

This product uses the TMDB API but is not endorsed or certified by TMDB.

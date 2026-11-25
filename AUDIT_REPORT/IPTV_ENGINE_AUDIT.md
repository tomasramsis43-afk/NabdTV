# IPTV engine audit (static reading; Not Tested at runtime)

## M3U
- Parser tolerates missing/garbage lines (EXTINF without URL is dropped, URL without EXTINF ignored), Arabic/UTF-8, quoted titles, catch-up attributes, logos, tvg-id/chno.
- Cached playlist is now read line by line (parseFile). Network body and Xtream JSON are still whole Strings (remaining risk for 100k+ lists).
- Duplicate channels are kept (no de-duplication); this is a product decision, not changed.

## Xtream
- Failure in one section (categories, movies, series) is caught per call; missing login now gives a clear error instead of NPE.
- Not Tested against a real or mocked server (no Xtream tests exist).

## EPG (fixed in this branch)
- Download is now written to a .part file and swapped in only when complete (before: a dropped connection could leave a truncated XML that looked fresh for 6 h).
- Corrupted/truncated XMLTV keeps the programmes read before the damage instead of losing the whole guide.
- Duplicate programmes (same start + title) are removed.
- Load failures are logged through Diag instead of being swallowed.
- Already good: streaming XmlPull parser, 2-pass with channel index, time window filter (-3 d / +36 h), gzip detection, disk cache keyed by file/channel list/overrides, 6 h refresh, runs on IO and never blocks channel playback.
- Not Tested: parser needs Robolectric/instrumented tests (android.util.Xml).

# Official title logos

jolo shows transparent title logos on the movie/series detail screen, the Home hero and poster cards.
**No account, no API key, no paid service.** When no verified logo exists the plain title is shown (existing style).

## Source that works
Wikidata property **P154 "logo image"** → a file on **Wikimedia Commons** (free licence or public domain).
Both are public HTTP APIs (a descriptive `User-Agent` is sent, as Wikimedia asks). Coverage is mostly well-known
international titles; most Arabic productions have no logo on Wikidata, so they keep the text title.
Run `tools/logo_probe.py` (or the *Logo source probe* workflow) to re-measure coverage — results land in
`tools/logo_probe_result.txt`.

## Matching (never guess)
A logo is used only when exactly one Wikidata item has: the same title (exact, normalised; Arabic or English),
the same kind (film vs TV series — a "film series"/franchise is rejected) and a compatible year (±1, from the
detail screen or from "Title (2019)"). Duplicate titles ("The Office") get no logo unless the year picks one.
The downloaded picture must be SVG/PNG, wide (1.15–12 : 1), with a transparent border — photos, posters and
JPGs are rejected. See `LogoMatch.kt` and `LogoMatchTest.kt`.

## Cache
`filesDir/logos/` — found logos are kept forever, "no logo" answers for 14 days. Offline or failed requests are
never cached as "no logo". Two lookups at a time, off the UI thread, de-duplicated per title.
Settings → Appearance → *Official title logos* turns the whole feature (and its downloads) off.

## Manual logos (override everything)
Put files in `filesDir/logos/manual/` (app-private; push with `adb` or call `Logos.addManual(title, bytes)`):
* `<normalised title>.png` — lowercase title with only letters/digits, e.g. `breakingbad.png`, `بابالحارة.png`
* `manual.json` — `{"Breaking Bad": "https://example.com/logo.png"}` (downloaded once, then cached)
Manual logos skip the transparency check, so you can supply any decodable image.

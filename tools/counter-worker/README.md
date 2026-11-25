# Device counter (Cloudflare Worker)

Counts how many devices use the app, without identifying any of them.

- `install`: the app sends one hit the first time it runs after this feature is on.
- `w:2026-W48`: one hit per week the app is opened, so the number is the devices active that week.

## Deploy (free plan, about 5 minutes)
1. Install Node, then in this folder run `npx wrangler login`.
2. `npx wrangler kv namespace create COUNTS` and put the printed `id` in `wrangler.toml`.
3. `npx wrangler secret put STATS_KEY` and type any long random text (it protects the numbers).
4. `npx wrangler deploy`. It prints the worker address, for example `https://app-counter.<you>.workers.dev`.
5. Put that address in `UsageCount.URL` in `app/src/main/java/com/nabd/tv/UsageCount.kt` (or send it to Claude).

## Read the numbers
Open `https://app-counter.<you>.workers.dev/stats?key=<STATS_KEY>` in a browser.

## Limits
- Anyone who knows the address can add to the count, so treat it as an estimate.
- Reinstalling counts as a new install; the weekly number is the better "active devices" figure.
- KV allows 1000 writes a day on the free plan, enough for about 900 devices a day.

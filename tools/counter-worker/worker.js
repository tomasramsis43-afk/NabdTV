// Anonymous device counter for the app, as a Cloudflare Worker with one KV namespace (binding name: COUNTS).
// The app sends two kinds of hit and nothing else: "install" (once per install) and "w:YYYY-Www" (once per week per install).
// No identifier, no device details and no IP address is stored. Read the numbers at /stats?key=<STATS_KEY>.
export default {
  async fetch(req, env) {
    const url = new URL(req.url);

    if (req.method === "POST" && url.pathname === "/hit") {
      const kind = url.searchParams.get("k") || "";
      if (!/^(install|w:\d{4}-W\d{2})$/.test(kind)) return new Response("bad", { status: 400 });
      const key = "c:" + kind;
      const n = parseInt((await env.COUNTS.get(key)) || "0", 10) + 1;
      await env.COUNTS.put(key, String(n));
      return new Response("ok");
    }

    if (req.method === "GET" && url.pathname === "/stats") {
      if (!env.STATS_KEY || url.searchParams.get("key") !== env.STATS_KEY) return new Response("forbidden", { status: 403 });
      const out = { installs: 0, weeks: {} };
      let cursor;
      do {
        const page = await env.COUNTS.list({ prefix: "c:", cursor });
        for (const k of page.keys) {
          const v = parseInt((await env.COUNTS.get(k.name)) || "0", 10);
          const kind = k.name.slice(2);
          if (kind === "install") out.installs = v; else out.weeks[kind.slice(2)] = v;
        }
        cursor = page.list_complete ? undefined : page.cursor;
      } while (cursor);
      return new Response(JSON.stringify(out, null, 2), { headers: { "content-type": "application/json" } });
    }

    return new Response("counter");
  },
};

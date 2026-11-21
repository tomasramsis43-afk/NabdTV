#!/usr/bin/env python3
"""Probe: which free, key-less sources really give verified title logos?  (Wikidata P154 -> Wikimedia Commons)

Mirrors the matching rules used by the app (LogoMatch.kt): exact normalised label/alias match, content type
(film vs TV series) from P31, release year +-1 when known, ambiguity => no logo.
"""
import json, re, sys, time, urllib.parse, urllib.request, io

UA = "NabdTV/2.0 (https://github.com/tomasramsis43-afk/NabdTV; logo probe)"
FILM = {"Q11424", "Q24862", "Q202866", "Q506240", "Q226730", "Q24869", "Q157443", "Q2431196", "Q20650540", "Q1054574", "Q4263842", "Q8274", "Q93204", "Q645928"}
SERIES = {"Q5398426", "Q1259759", "Q526877", "Q15416", "Q63952888", "Q21191270", "Q117467246", "Q581714", "Q1107", "Q196600", "Q15961987", "Q47433", "Q1983062", "Q28464168", "Q3464665", "Q43232"}

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read(), r.headers

def api(**p):
    p["format"] = "json"
    return json.loads(get("https://www.wikidata.org/w/api.php?" + urllib.parse.urlencode(p))[0])

def norm(t):
    return re.sub(r"[^\w]", "", t.lower(), flags=re.U)

def year_of(claims, pid):
    out = []
    for c in claims.get(pid, []):
        try:
            out.append(int(c["mainsnak"]["datavalue"]["value"]["time"][1:5]))
        except Exception:
            pass
    return out

def resolve(title, kind, year, lang):
    s = api(action="wbsearchentities", search=title, language=lang, uselang=lang, type="item", limit=10)
    ids = [x["id"] for x in s.get("search", [])]
    if not ids:
        return None, "no search hit"
    ents = api(action="wbgetentities", ids="|".join(ids), props="claims|labels|aliases|descriptions", languages="en|ar")["entities"]
    want = norm(title)
    cands = []
    for q in ids:
        e = ents.get(q, {})
        names = [v["value"] for v in e.get("labels", {}).values()] + [a["value"] for v in e.get("aliases", {}).values() for a in v]
        if want not in {norm(n) for n in names}:
            continue
        cl = e.get("claims", {})
        classes = {c["mainsnak"]["datavalue"]["value"]["id"] for c in cl.get("P31", []) if "datavalue" in c["mainsnak"]}
        is_film, is_series = bool(classes & FILM), bool(classes & SERIES)
        if (kind == "movie" and not is_film) or (kind == "series" and not is_series):
            continue
        ys = year_of(cl, "P577") + year_of(cl, "P580")
        if year and ys and not any(abs(y - year) <= 1 for y in ys):
            continue
        logo = [c["mainsnak"]["datavalue"]["value"] for c in cl.get("P154", []) if "datavalue" in c["mainsnak"]]
        cands.append((q, ys[:2], logo[:1], sorted(classes)[:3]))
    if not cands:
        return None, "no verified candidate (label/type/year)"
    if len(cands) > 1 and not year:
        return None, "ambiguous: %s" % [c[0] for c in cands]
    withlogo = [c for c in cands if c[2]]
    if not withlogo:
        return None, "matched %s but Wikidata has no logo (P154)" % cands[0][0]
    if len(withlogo) > 1 and len(cands) > 1:
        return None, "ambiguous with logos"
    return withlogo[0], "ok"

def check_image(fname):
    url = "https://commons.wikimedia.org/wiki/Special:FilePath/%s?width=600" % urllib.parse.quote(fname.replace(" ", "_"))
    data, h = get(url)
    info = "%s %dB" % (h.get("Content-Type"), len(data))
    try:
        from PIL import Image
        im = Image.open(io.BytesIO(data)); info += " %s %dx%d" % (im.mode, *im.size)
    except Exception as ex:
        info += " (pil: %s)" % ex
    return info

SAMPLES = [
    ("Inception", "movie", 2010, "en"), ("The Dark Knight", "movie", 2008, "en"), ("Avatar", "movie", 2009, "en"),
    ("Joker", "movie", 2019, "en"), ("Titanic", "movie", 1997, "en"), ("Interstellar", "movie", 2014, "en"),
    ("Parasite", "movie", 2019, "en"), ("Gladiator", "movie", 2000, "en"), ("Joker", "movie", None, "en"),
    ("Breaking Bad", "series", 2008, "en"), ("Game of Thrones", "series", 2011, "en"), ("Stranger Things", "series", 2016, "en"),
    ("The Crown", "series", 2016, "en"), ("Friends", "series", 1994, "en"), ("The Office", "series", 2005, "en"),
    ("The Office", "series", None, "en"), ("Dark", "series", 2017, "en"), ("Narcos", "series", 2015, "en"),
    ("Peaky Blinders", "series", 2013, "en"), ("Squid Game", "series", 2021, "en"), ("The Witcher", "series", 2019, "en"),
    ("Money Heist", "series", 2017, "en"), ("باب الحارة", "series", 2006, "ar"), ("الاختيار", "series", 2020, "ar"),
    ("الفيل الأزرق", "movie", 2014, "ar"), ("ولاد رزق", "movie", 2015, "ar"), ("جعفر العمدة", "series", 2023, "ar"),
]

def main():
    ok = 0
    for title, kind, year, lang in SAMPLES:
        try:
            res, why = resolve(title, kind, year, lang)
            if res:
                info = check_image(res[2][0])
                ok += 1
                print("OK   %-18s %-6s %-5s -> %s | %s | %s | P31=%s" % (title, kind, year, res[0], res[2][0], info, res[3]))
            else:
                print("MISS %-18s %-6s %-5s -> %s" % (title, kind, year, why))
        except Exception as ex:
            print("ERR  %-18s %s" % (title, ex))
        time.sleep(0.4)
    print("\nlogos resolved: %d / %d" % (ok, len(SAMPLES)))

main()

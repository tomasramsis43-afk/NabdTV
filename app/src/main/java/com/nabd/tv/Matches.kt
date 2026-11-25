package com.nabd.tv

/**
 * "Today's matches": finds football-style fixtures ("A vs B", "A × B", "A ضد B") in the programme guide of the live channels and
 * groups the same fixture shown on several channels into one entry. Pure logic, no Android types, so it is unit tested.
 */
object Matches {
    class Match(val home: String, val away: String, val start: Long, val stop: Long, val channels: List<Channel>) {
        fun isLive(now: Long) = now in start until stop
    }

    private val strong = Regex("""\s+(?:vs\.?|v|x|×|ضد|مقابل)\s+""", RegexOption.IGNORE_CASE)
    private val dash = Regex("""\s+[-–—]\s+""")
    private val replay = Regex("""إعادة|اعادة|ملخص|أهداف|اهداف|replay|highlights|recap|rerun""", RegexOption.IGNORE_CASE)
    private val hint = Regex("""مباراة|مباشر|دوري|كأس|كاس|\blive\b|\bmatch\b|league|cup|\bfc\b""", RegexOption.IGNORE_CASE)
    private val noise = Regex("""^(?:مباراة|مباشر|بث|live|match|full|football|كرة القدم)\s+|\s+(?:live|مباشر|hd|fhd)$""", RegexOption.IGNORE_CASE)
    private val clubWord = Regex("""\b(?:fc|cf|sc|ac|club|نادي|فريق)\b""", RegexOption.IGNORE_CASE)

    private fun clean(side: String): String {
        var s = side.trim().trim('.', ',', '،', '-', '–')
        repeat(3) { s = noise.replace(s, "").trim() }
        return s
    }

    private fun okSide(s: String): Boolean {
        val words = s.split(Regex("\\s+")).filter { it.isNotBlank() }
        return s.length in 2..30 && words.size in 1..4 && s.any { it.isLetter() }
    }

    private val nonTeam = Regex("""league|\bcup\b|دوري|كأس|كاس|\bmatch\b|\bnews\b|أخبار|اخبار|\bshow\b|برنامج""", RegexOption.IGNORE_CASE)

    private fun splitSides(part: String, title: String): Pair<String, String>? {
        val m = strong.find(part)
        if (m != null) return part.substring(0, m.range.first) to part.substring(m.range.last + 1)
        val d = dash.findAll(part).toList()
        if (d.size != 1 || !hint.containsMatchIn(title)) return null
        return part.substring(0, d[0].range.first) to part.substring(d[0].range.last + 1)
    }

    /** Home and away team of a programme title, or null when it does not look like a fixture (or is a replay). */
    fun parse(title: String): Pair<String, String>? {
        if (replay.containsMatchIn(title)) return null
        val t = title.replace(Regex("""\(.*?\)|\[.*?]"""), " ")
        for (part in t.split(Regex("[:|]"))) {
            val sides = splitSides(part, title) ?: continue
            // A league name often trails the second team after a dash: "A v B - Premier League".
            val right = dash.split(sides.second).first()
            val a = clean(sides.first); val b = clean(right)
            if (okSide(a) && okSide(b) && !nonTeam.containsMatchIn(a) && !nonTeam.containsMatchIn(b)) return a to b
        }
        return null
    }

    private fun teamKey(s: String) = clubWord.replace(Classifier.normalize(s), " ").replace(Regex("\\s+"), " ").trim()

    /** Fixtures on air now or starting within [horizonMs], newest first by start (live ones first). */
    fun detect(epg: Map<String, List<Prog>>, channels: List<Channel>, now: Long, horizonMs: Long = 24 * 3_600_000L, limit: Int = 20): List<Match> {
        class Acc(val home: String, val away: String, var start: Long, var stop: Long, val chans: ArrayList<Channel> = ArrayList())
        val byKey = HashMap<String, ArrayList<Acc>>()
        for (ch in channels) {
            val progs = epg[ch.url] ?: continue
            val sports = Classifier.genre(ch.name, ch.group) == Genre.Sports
            for (p in progs) {
                if (p.stop <= now || p.start >= now + horizonMs) continue
                if (!sports && !hint.containsMatchIn(p.title)) continue
                val (h, a) = parse(p.title) ?: continue
                val k = teamKey(h) + "|" + teamKey(a)
                if (k.length < 3) continue
                val list = byKey.getOrPut(k) { ArrayList() }
                // The same fixture is listed with slightly different times by different channels: one entry within 3 hours.
                val acc = list.firstOrNull { kotlin.math.abs(it.start - p.start) <= 3 * 3_600_000L }
                    ?: Acc(h, a, p.start, p.stop).also { list += it }
                if (acc.chans.none { it.url == ch.url }) acc.chans += ch
                if (p.start < acc.start) acc.start = p.start
                if (p.stop > acc.stop) acc.stop = p.stop
            }
        }
        return byKey.values.flatten()
            .map { Match(it.home, it.away, it.start, it.stop, it.chans) }
            .sortedWith(compareBy<Match> { !it.isLive(now) }.thenBy { it.start })
            .take(limit)
    }
}

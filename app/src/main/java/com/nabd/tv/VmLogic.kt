package com.nabd.tv

/** Pure helpers used by [AppViewModel]; kept free of Android state so they can be unit-tested on the JVM. */
object VmLogic {
    /** Episode after [url] in [episodes], or null when [url] is not in the list or is the last one. */
    fun nextEpisode(episodes: List<Episode>, url: String): Episode? {
        val i = episodes.indexOfFirst { it.url == url }
        return if (i >= 0) episodes.getOrNull(i + 1) else null
    }

    private val EP_NAME = Regex("^(.*?)\\s·\\s(?:\\u0645(\\d+)\\s\\u062D(\\d+)|S(\\d+)\\sE(\\d+))$")

    /**
     * Old history entries are named "Series · م1 ح2" (Arabic) or "Series · S01 E02" (English) and carry no episode
     * metadata. Returns (series name, season, episode) or null when [name] is not an episode label.
     */
    fun parseEpisodeName(name: String): Triple<String, Int, Int>? {
        val m = EP_NAME.find(name.trim()) ?: return null
        val season = m.groupValues[2].ifEmpty { m.groupValues[4] }.toIntOrNull() ?: return null
        val num = m.groupValues[3].ifEmpty { m.groupValues[5] }.toIntOrNull() ?: return null
        val series = m.groupValues[1].trim()
        return if (series.isEmpty()) null else Triple(series, season, num)
    }

    /** "Watch later" is stored as a set of "addedAtMillis|url" entries; this rebuilds the list, newest first. Malformed entries are skipped. */
    fun parseWatchLater(entries: Set<String>): List<String> = entries
        .mapNotNull { e -> val i = e.indexOf('|'); if (i <= 0) null else e.substring(0, i).toLongOrNull()?.let { it to e.substring(i + 1) } }
        .sortedByDescending { it.first }.map { it.second }

    /** Series to watch for new episodes: favourites first, then series being watched, then Watch later; distinct and capped. */
    fun followedSeries(favorites: Collection<String>, watched: Collection<String>, later: Collection<String>, cap: Int = 20): List<String> =
        (favorites.filter { it.startsWith("series:") } + watched + later.filter { it.startsWith("series:") }).distinct().take(cap)

    /** The most recent episode of a series (highest season, then number). */
    fun newestEpisode(eps: List<Episode>): Episode? = eps.maxWithOrNull(compareBy({ it.season }, { it.num }))

    /**
     * The episode's OWN picture, or null. Providers often send the series cover as every episode's image; a picture
     * equal to the series poster, or shared by several episodes, is not an episode image and must not be used.
     */
    fun episodeImage(e: Episode, all: List<Episode>, seriesPoster: String?): String? {
        val img = e.image?.takeIf { it.isNotBlank() } ?: return null
        if (img == seriesPoster) return null
        if (all.count { it.image == img } > 1) return null
        return img
    }

    /** Episode before [url] in [episodes], or null when [url] is not in the list or is the first one. */
    fun previousEpisode(episodes: List<Episode>, url: String): Episode? {
        val i = episodes.indexOfFirst { it.url == url }
        return if (i > 0) episodes.getOrNull(i - 1) else null
    }

    /** User-facing description of a network/parsing failure, in the selected language ([str] resolves a string resource). */
    fun friendlyError(e: Exception, str: (Int) -> String = { S(it) }): String = when (e) {
        is java.net.UnknownHostException -> str(R.string.vm_err_host)
        is java.net.SocketTimeoutException -> str(R.string.vm_err_timeout)
        is java.net.ConnectException -> str(R.string.vm_err_refused)
        is IllegalArgumentException -> str(R.string.vm_err_bad_address) + (e.message ?: "")
        is org.json.JSONException -> str(R.string.vm_err_json)
        else -> e.message ?: str(R.string.vm_err_unknown)
    }

    /** Rating text ("7,5", " 8.1 ") as a number, or 0 when missing / not numeric. */
    fun ratingOf(c: Channel): Double = c.rating?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0

    /**
     * "Trending": well-rated AND fresh. Score = rating (0-10) plus a recency bonus from the provider's added date
     * (<=30 days +2.5, <=90 days +1.5, <=1 year +0.5). Unrated entries are dropped. Honest proxy: it uses the
     * provider's own data, not live worldwide viewing numbers.
     */
    fun trending(l: List<Channel>, n: Int = 10, nowSec: Long = System.currentTimeMillis() / 1000): List<Channel> {
        fun score(c: Channel): Double {
            val ageDays = if (c.added > 0) (nowSec - c.added) / 86_400.0 else Double.MAX_VALUE
            val bonus = when { ageDays <= 30 -> 2.5; ageDays <= 90 -> 1.5; ageDays <= 365 -> 0.5; else -> 0.0 }
            return ratingOf(c) + bonus
        }
        return l.filter { ratingOf(it) > 0.0 }.sortedByDescending { score(it) }.take(n)
    }

    /** Highest-rated entries (rating > 0), best first. */
    fun topRated(l: List<Channel>, n: Int = 10): List<Channel> =
        l.filter { ratingOf(it) > 0.0 }.sortedByDescending { ratingOf(it) }.take(n)

    /**
     * One entry per normalized channel name; among duplicates the lowest [rank] wins (first one on a tie).
     * Entries with a blank key are always kept. Order of [l] is preserved.
     */
    fun dedupeSources(l: List<Channel>, rank: (Channel) -> Int): List<Channel> {
        val best = HashMap<String, Channel>()
        for (c in l) {
            val k = Classifier.normalize(c.name)
            if (k.isBlank()) continue
            val b = best[k]
            if (b == null || rank(c) < rank(b)) best[k] = c
        }
        return l.filter { val k = Classifier.normalize(it.name); k.isBlank() || best[k]?.url == it.url }
    }

    /** Programme airing at [now] and the one after it; (null, null) when nothing is airing. */
    fun nowNext(progs: List<Prog>?, now: Long): Pair<Prog?, Prog?> {
        val l = progs ?: return null to null
        val i = l.indexOfFirst { now >= it.start && now < it.stop }
        return if (i < 0) null to null else l[i] to l.getOrNull(i + 1)
    }

    /** Daily viewing limit: only enforced when a limit and a PIN exist and the PIN has not been entered. */
    fun limitReached(limitMinutes: Int, hasPin: Boolean, unlocked: Boolean, watchedSeconds: Long): Boolean =
        limitMinutes > 0 && hasPin && !unlocked && watchedSeconds >= limitMinutes * 60L

    /** Toast shown after trying to schedule a recording. [scheduled] = false means it already existed. */
    fun recordMessage(scheduled: Boolean, clash: Boolean, str: (Int) -> String = { S(it) }): String = when {
        !scheduled -> str(R.string.vm_rec_exists)
        clash -> str(R.string.vm_rec_clash)
        else -> str(R.string.vm_rec_ok)
    }

    /** One line of the "problem channels" report. */
    fun problemLine(name: String, dead: Boolean, failures: Int, str: (Int) -> String = { S(it) }): String =
        name + " — " + str(if (dead) R.string.vm_prob_dead else R.string.vm_prob_slow) + " · " + failures + " " + str(R.string.vm_prob_fails)
}

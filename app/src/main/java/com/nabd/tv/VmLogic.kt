package com.nabd.tv

/** Pure helpers used by [AppViewModel]; kept free of Android state so they can be unit-tested on the JVM. */
object VmLogic {
    /** Episode after [url] in [episodes], or null when [url] is not in the list or is the last one. */
    fun nextEpisode(episodes: List<Episode>, url: String): Episode? {
        val i = episodes.indexOfFirst { it.url == url }
        return if (i >= 0) episodes.getOrNull(i + 1) else null
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

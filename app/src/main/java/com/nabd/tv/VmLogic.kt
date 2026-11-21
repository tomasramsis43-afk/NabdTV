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

    /** Arabic, user-facing description of a network/parsing failure. */
    fun friendlyError(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "تعذّر الوصول للسيرفر (الاسم غير صحيح أو لا يوجد إنترنت)"
        is java.net.SocketTimeoutException -> "انتهت مهلة الاتصال بالسيرفر"
        is java.net.ConnectException -> "السيرفر رفض الاتصال، تأكد من العنوان والبورت"
        is IllegalArgumentException -> "العنوان غير صحيح: " + (e.message ?: "")
        is org.json.JSONException -> "رد غير متوقع من السيرفر (الخدمة غير مفعّلة في اشتراكك أو الرد تالف)"
        else -> e.message ?: "خطأ غير معروف"
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
    fun recordMessage(scheduled: Boolean, clash: Boolean): String = when {
        !scheduled -> "مجدول مسبقاً"
        clash -> "تمت الجدولة، لكنه يتعارض مع تسجيل آخر"
        else -> "تمت جدولة التسجيل"
    }
}

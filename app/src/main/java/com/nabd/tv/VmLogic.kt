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
}

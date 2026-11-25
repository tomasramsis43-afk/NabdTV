package com.nabd.tv

import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Calendar
import java.util.Locale

/**
 * Anonymous device count. Once per install and once per week the app sends ONE hit to the developer's counter
 * (see tools/counter-worker). Nothing identifies the device: no id, no model, no account. The user can switch it off in Settings.
 * Inactive until [URL] is set.
 */
object UsageCount {
    /** Address of the deployed counter worker, e.g. "https://app-counter.name.workers.dev". Blank = nothing is ever sent. */
    const val URL = ""

    /** ISO week of [ms] as "2026-W48" (weeks start on Monday, week 1 holds January 4th). */
    fun weekKey(ms: Long): String {
        val c = Calendar.getInstance(Locale.ROOT).apply {
            firstDayOfWeek = Calendar.MONDAY; minimalDaysInFirstWeek = 4; timeInMillis = ms
        }
        return "%d-W%02d".format(Locale.ROOT, c.getWeekYear(), c.get(Calendar.WEEK_OF_YEAR))
    }

    /** What still has to be sent: "install" the first time, then the current week once. */
    fun due(counted: Boolean, lastWeek: String, now: Long): List<String> {
        val out = ArrayList<String>(2)
        if (!counted) out += "install"
        val w = "w:" + weekKey(now)
        if (w != lastWeek) out += w
        return out
    }

    fun sendAsync(store: Store) {
        if (URL.isBlank() || !store.usageCount) return
        val kinds = due(store.countedInstall, store.countedWeek, System.currentTimeMillis())
        if (kinds.isEmpty()) return
        val client = Net.client(6, 6)
        for (k in kinds) {
            val req = okhttp3.Request.Builder()
                .url(URL.trimEnd('/') + "/hit?k=" + java.net.URLEncoder.encode(k, "UTF-8"))
                .post(okhttp3.RequestBody.Companion.run { ByteArray(0).toRequestBody() }).build()
            client.newCall(req).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { Diag.note("usage-count", e) }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use { if (it.isSuccessful) { if (k == "install") store.countedInstall = true else store.countedWeek = k } }
                }
            })
        }
    }
}

package com.nabd.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** Local persistence (SharedPreferences + JSON). Nothing leaves the device. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("nabd2", Context.MODE_PRIVATE)

    /* ---------- simple settings ---------- */
    var startSection: String
        get() = p.getString("startSection", "Home") ?: "Home"
        set(v) = p.edit().putString("startSection", v).apply()
    var hideDead: Boolean
        get() = p.getBoolean("hideDead", true)
        set(v) = p.edit().putBoolean("hideDead", v).apply()
    var autoRefresh: Boolean
        get() = p.getBoolean("autoRefresh", true)
        set(v) = p.edit().putBoolean("autoRefresh", v).apply()
    var prefSubLang: String
        get() = p.getString("subLang", "ar") ?: "ar"
        set(v) = p.edit().putString("subLang", v).apply()
    var prefAudioLang: String
        get() = p.getString("audioLang", "ar") ?: "ar"
        set(v) = p.edit().putString("audioLang", v).apply()
    var subSize: Float
        get() = p.getFloat("subSize", 0.06f)
        set(v) = p.edit().putFloat("subSize", v).apply()
    var subColor: Int
        get() = p.getInt("subColor", 0xFFFFFFFF.toInt())
        set(v) = p.edit().putInt("subColor", v).apply()
    var subBackground: Boolean
        get() = p.getBoolean("subBg", true)
        set(v) = p.edit().putBoolean("subBg", v).apply()
    var catchupOffsetHours: Int
        get() = p.getInt("catchupOffset", 0)
        set(v) = p.edit().putInt("catchupOffset", v).apply()
    var tmdbKey: String
        get() = p.getString("tmdbKey", "") ?: ""
        set(v) = p.edit().putString("tmdbKey", v.trim()).apply()
    var filterWords: String
        get() = p.getString("filterWords", "") ?: ""
        set(v) = p.edit().putString("filterWords", v).apply()
    val filterList: List<String> get() = filterWords.split(',', '،', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /* ---------- string sets ---------- */
    private val setCache = HashMap<String, Set<String>>()
    private fun strSet(k: String): Set<String> = setCache.getOrPut(k) { p.getStringSet(k, emptySet())!!.toSet() }
    private fun putStrSet(k: String, v: Set<String>) { setCache[k] = v.toSet(); p.edit().putStringSet(k, v).apply() }

    var favorites: Set<String>
        get() = strSet("fav")
        set(v) = putStrSet("fav", v)
    var pinnedGroups: Set<String>
        get() = strSet("pinnedGroups")
        set(v) = putStrSet("pinnedGroups", v)
    var hiddenGroups: Set<String>
        get() = strSet("hiddenGroups")
        set(v) = putStrSet("hiddenGroups", v)
    var lockedGroups: Set<String>
        get() = strSet("lockedGroups")
        set(v) = putStrSet("lockedGroups", v)
    /** Channel urls that the user manually hid. */
    var hiddenChannels: Set<String>
        get() = strSet("hiddenChannels")
        set(v) = putStrSet("hiddenChannels", v)
    /** Titles (lower-case) whose every upcoming airing should be recorded. */
    var autoRecordTitles: Set<String>
        get() = strSet("autoRecordTitles")
        set(v) = putStrSet("autoRecordTitles", v)

    /* ---------- manual EPG overrides: channel url -> xmltv channel id ---------- */
    fun epgOverrides(): Map<String, String> {
        val o = JSONObject(p.getString("epgOverrides", "{}") ?: "{}")
        val m = HashMap<String, String>()
        for (k in o.keys()) m[k] = o.optString(k)
        return m
    }
    fun setEpgOverride(channelUrl: String, xmlId: String?) {
        val o = JSONObject(p.getString("epgOverrides", "{}") ?: "{}")
        if (xmlId.isNullOrBlank()) o.remove(channelUrl) else o.put(channelUrl, xmlId)
        p.edit().putString("epgOverrides", o.toString()).apply()
    }

    /* ---------- parental control ---------- */
    var parentalLevel: Maturity
        get() = runCatching { Maturity.valueOf(p.getString("parentalLevel", "Teen")!!) }.getOrDefault(Maturity.Teen)
        set(v) = p.edit().putString("parentalLevel", v.name).apply()
    var hideLocked: Boolean
        get() = p.getBoolean("hideLocked", false)
        set(v) = p.edit().putBoolean("hideLocked", v).apply()
    val hasPin: Boolean get() = !p.getString("pinHash", "").isNullOrEmpty()

    private fun hash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    fun setPin(pin: String) {
        val salt = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        p.edit().putString("pinSalt", salt).putString("pinHash", hash(pin, salt)).apply()
    }
    fun clearPin() { p.edit().remove("pinSalt").remove("pinHash").apply() }
    fun checkPin(pin: String): Boolean {
        val salt = p.getString("pinSalt", "") ?: ""
        val h = p.getString("pinHash", "") ?: ""
        return h.isNotEmpty() && hash(pin, salt) == h
    }

    /* ---------- watch history ---------- */
    private var histCache: LinkedHashMap<String, HistoryEntry>? = null

    private fun hist(): LinkedHashMap<String, HistoryEntry> {
        histCache?.let { return it }
        val m = LinkedHashMap<String, HistoryEntry>()
        runCatching {
            val a = JSONArray(p.getString("history", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                m[o.getString("u")] = HistoryEntry(
                    o.getString("u"), o.optString("n"), o.optString("l").ifEmpty { null }, o.optString("g"),
                    runCatching { Kind.valueOf(o.optString("k")) }.getOrDefault(Kind.Live),
                    o.optLong("p"), o.optLong("d"), o.optLong("t"), o.optInt("c", 1)
                )
            }
        }
        histCache = m
        return m
    }

    private fun saveHist() {
        val a = JSONArray()
        hist().values.sortedByDescending { it.last }.take(400).forEach { e ->
            a.put(JSONObject().put("u", e.url).put("n", e.name).put("l", e.logo ?: "").put("g", e.group)
                .put("k", e.kind.name).put("p", e.position).put("d", e.duration).put("t", e.last).put("c", e.count))
        }
        p.edit().putString("history", a.toString()).apply()
    }

    fun history(): List<HistoryEntry> = hist().values.sortedByDescending { it.last }

    fun historyOf(url: String): HistoryEntry? = hist()[url]

    /** Records a watch session. [position]/[duration] are 0 for live channels. */
    fun recordWatch(c: Channel, kind: Kind, position: Long, duration: Long, countAsNew: Boolean) {
        val old = hist()[c.url]
        hist()[c.url] = HistoryEntry(
            c.url, c.name, c.logo, c.group, kind, position, duration, System.currentTimeMillis(),
            (old?.count ?: 0) + if (countAsNew) 1 else 0
        )
        saveHist()
    }

    fun clearHistory() { hist().clear(); saveHist() }

    /* ---------- stream health ---------- */
    private var healthCache: HashMap<String, HealthRecord>? = null

    private fun healthMap(): HashMap<String, HealthRecord> {
        healthCache?.let { return it }
        val m = HashMap<String, HealthRecord>()
        runCatching {
            val o = JSONObject(p.getString("health", "{}"))
            for (k in o.keys()) {
                val r = o.getJSONObject(k)
                m[k] = HealthRecord(
                    runCatching { Health.valueOf(r.optString("s")) }.getOrDefault(Health.Unknown),
                    r.optLong("l"), r.optLong("t"), r.optInt("f")
                )
            }
        }
        healthCache = m
        return m
    }

    fun health(url: String): HealthRecord? = healthMap()[url]

    fun setHealth(url: String, r: HealthRecord) { healthMap()[url] = r }

    fun saveHealth() {
        val o = JSONObject()
        val m = healthMap()
        // keep the store bounded: drop oldest beyond 6000 entries
        val keep = if (m.size > 6000) m.entries.sortedByDescending { it.value.checked }.take(6000) else m.entries.toList()
        keep.forEach { (k, r) -> o.put(k, JSONObject().put("s", r.state.name).put("l", r.latencyMs).put("t", r.checked).put("f", r.failures)) }
        p.edit().putString("health", o.toString()).apply()
    }

    fun clearHealth() { healthMap().clear(); saveHealth() }

    /* ---------- recordings ---------- */
    fun recordings(): List<Recording> {
        val out = ArrayList<Recording>()
        runCatching {
            val a = JSONArray(p.getString("recordings", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out += Recording(
                    o.getString("id"), o.optString("cn"), o.optString("cu"), o.optString("t"),
                    o.optLong("s"), o.optLong("e"),
                    runCatching { RecStatus.valueOf(o.optString("st")) }.getOrDefault(RecStatus.Scheduled),
                    o.optString("f").ifEmpty { null }
                )
            }
        }
        return out
    }

    fun saveRecordings(list: List<Recording>) {
        val a = JSONArray()
        list.forEach { r ->
            a.put(JSONObject().put("id", r.id).put("cn", r.channelName).put("cu", r.channelUrl).put("t", r.title)
                .put("s", r.start).put("e", r.stop).put("st", r.status.name).put("f", r.file ?: ""))
        }
        p.edit().putString("recordings", a.toString()).apply()
    }

    /* ---------- reminders ---------- */
    fun reminders(): List<Reminder> {
        val out = ArrayList<Reminder>()
        runCatching {
            val a = JSONArray(p.getString("reminders", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out += Reminder(o.getString("id"), o.optString("c"), o.optString("t"), o.optLong("s"))
            }
        }
        return out
    }

    fun saveReminders(list: List<Reminder>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("c", it.channelName).put("t", it.title).put("s", it.start)) }
        p.edit().putString("reminders", a.toString()).apply()
    }

    /* ---------- custom groups: name -> channel urls ---------- */
    fun customGroups(): Map<String, List<String>> {
        val o = JSONObject(p.getString("customGroups", "{}") ?: "{}")
        val m = LinkedHashMap<String, List<String>>()
        for (k in o.keys()) {
            val a = o.getJSONArray(k)
            m[k] = (0 until a.length()).map { a.getString(it) }
        }
        return m
    }

    fun saveCustomGroups(m: Map<String, List<String>>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        p.edit().putString("customGroups", o.toString()).apply()
    }
}

package com.nabd.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** Local persistence (SharedPreferences + JSON). Nothing leaves the device. */
class Store(private val ctx: Context) {
    /** Global data shared by every profile (provider-level state, PIN, recordings, health…). */
    private val g = ctx.getSharedPreferences("nabd2", Context.MODE_PRIVATE)
    /** Per-profile data. The "main" profile keeps using the original file, so nothing is lost on upgrade. */
    private var p = prefsFor(g.getString("profileId", "main") ?: "main")

    private fun prefsFor(id: String) =
        if (id == "main") g else ctx.getSharedPreferences("nabd2_p_$id", Context.MODE_PRIVATE)

    data class Profile(val id: String, val name: String, val kids: Boolean)

    fun profiles(): List<Profile> {
        val out = ArrayList<Profile>()
        runCatching {
            val a = JSONArray(g.getString("profiles", "[]"))
            for (i in 0 until a.length()) a.getJSONObject(i).let { out += Profile(it.getString("id"), it.optString("n"), it.optBoolean("k")) }
        }
        if (out.none { it.id == "main" }) out.add(0, Profile("main", "الرئيسي", false))
        return out
    }

    private fun saveProfiles(l: List<Profile>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("n", it.name).put("k", it.kids)) }
        g.edit().putString("profiles", a.toString()).apply()
    }

    val currentProfile: Profile
        get() { val id = g.getString("profileId", "main") ?: "main"; return profiles().firstOrNull { it.id == id } ?: profiles().first() }

    fun addProfile(name: String, kids: Boolean) {
        val id = java.lang.Long.toString(System.currentTimeMillis(), 36)
        saveProfiles(profiles() + Profile(id, name.trim().ifEmpty { "ملف" }, kids))
    }

    fun deleteProfile(id: String) {
        if (id == "main") return
        saveProfiles(profiles().filter { it.id != id })
        runCatching { ctx.deleteSharedPreferences("nabd2_p_$id") }
        if (g.getString("profileId", "main") == id) switchProfile("main")
    }

    fun switchProfile(id: String) {
        g.edit().putString("profileId", id).apply()
        p = prefsFor(id)
        setCache.clear(); histCache = null
    }

    /* ---------- simple settings ---------- */
    var startSection: String
        get() = p.getString("startSection", "Home") ?: "Home"
        set(v) = p.edit().putString("startSection", v).apply()
    var darkMode: Boolean
        get() = g.getBoolean("darkMode", false)
        set(v) = g.edit().putBoolean("darkMode", v).apply()
    var hideDead: Boolean
        get() = g.getBoolean("hideDead", true)
        set(v) = g.edit().putBoolean("hideDead", v).apply()
    var autoRefresh: Boolean
        get() = g.getBoolean("autoRefresh", true)
        set(v) = g.edit().putBoolean("autoRefresh", v).apply()
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

    /* ---------- professional player settings ---------- */

    /** Forward / rewind step used by the buttons, remote and double-tap. One of 5, 10, 15, 30, 60. */
    var seekInterval: Int
        get() = p.getInt("pl_seek", 10).let { if (it in listOf(5, 10, 15, 30, 60)) it else 10 }
        set(v) = p.edit().putInt("pl_seek", if (v in listOf(5, 10, 15, 30, 60)) v else 10).apply()

    /** Playback speed applied when a title starts. */
    var defaultSpeed: Float
        get() = p.getFloat("pl_speed", 1f).coerceIn(0.25f, 4f)
        set(v) = p.edit().putFloat("pl_speed", v.coerceIn(0.25f, 4f)).apply()

    /** Default video scaling mode (see [AspectMode]). */
    var defaultAspect: String
        get() = p.getString("pl_aspect", AspectMode.Fit.name) ?: AspectMode.Fit.name
        set(v) = p.edit().putString("pl_aspect", v).apply()

    var autoNext: Boolean
        get() = p.getBoolean("pl_autoNext", true)
        set(v) = p.edit().putBoolean("pl_autoNext", v).apply()

    var loopByDefault: Boolean
        get() = p.getBoolean("pl_loop", false)
        set(v) = p.edit().putBoolean("pl_loop", v).apply()

    /** Ask before resuming, always resume, or never resume. */
    var resumeMode: String
        get() = p.getString("pl_resume", ResumeMode.Always.name) ?: ResumeMode.Always.name
        set(v) = p.edit().putString("pl_resume", v).apply()

    /** Save the current position so the title can be resumed later. */
    var rememberPosition: Boolean
        get() = p.getBoolean("pl_remember", true)
        set(v) = p.edit().putBoolean("pl_remember", v).apply()

    var subEnabled: Boolean
        get() = p.getBoolean("pl_subOn", true)
        set(v) = p.edit().putBoolean("pl_subOn", v).apply()
    var subOutline: Boolean
        get() = p.getBoolean("pl_subOutline", true)
        set(v) = p.edit().putBoolean("pl_subOutline", v).apply()
    /** Distance from the bottom of the screen, as a fraction (0.04 = low, 0.32 = high). */
    var subPosition: Float
        get() = p.getFloat("pl_subPos", 0.08f).coerceIn(0.02f, 0.45f)
        set(v) = p.edit().putFloat("pl_subPos", v.coerceIn(0.02f, 0.45f)).apply()
    /** Subtitle timing offset in milliseconds (positive = subtitles appear later). */
    var subDelayMs: Int
        get() = p.getInt("pl_subDelay", 0).coerceIn(-10_000, 10_000)
        set(v) = p.edit().putInt("pl_subDelay", v.coerceIn(-10_000, 10_000)).apply()
    /** Character set used for external subtitle files ("auto" detects it). */
    var subEncoding: String
        get() = p.getString("pl_subEnc", "auto") ?: "auto"
        set(v) = p.edit().putString("pl_subEnc", v).apply()

    var audioBoost: Boolean
        get() = p.getBoolean("pl_boost", false)
        set(v) = p.edit().putBoolean("pl_boost", v).apply()

    /* Buffering & decoding are device-level, so they live in the global preferences. */
    var bufferProfile: String
        get() = g.getString("pl_buffer", BufferProfile.Balanced.name) ?: BufferProfile.Balanced.name
        set(v) = g.edit().putString("pl_buffer", v).apply()
    var decoderMode: String
        get() = g.getString("pl_decoder", DecoderMode.Auto.name) ?: DecoderMode.Auto.name
        set(v) = g.edit().putString("pl_decoder", v).apply()
    /** Audio/video sync correction in milliseconds (applied by the player's audio pipeline). */
    var audioDelayMs: Int
        get() = g.getInt("pl_audioDelay", 0).coerceIn(-2000, 2000)
        set(v) = g.edit().putInt("pl_audioDelay", v.coerceIn(-2000, 2000)).apply()

    /* Touch gestures (phones / tablets). */
    var gesturesEnabled: Boolean
        get() = p.getBoolean("pl_gestures", true)
        set(v) = p.edit().putBoolean("pl_gestures", v).apply()
    var gestureSeek: Boolean
        get() = p.getBoolean("pl_gSeek", true)
        set(v) = p.edit().putBoolean("pl_gSeek", v).apply()
    var gestureBrightness: Boolean
        get() = p.getBoolean("pl_gBright", true)
        set(v) = p.edit().putBoolean("pl_gBright", v).apply()
    var gestureVolume: Boolean
        get() = p.getBoolean("pl_gVolume", true)
        set(v) = p.edit().putBoolean("pl_gVolume", v).apply()
    var gestureDoubleTap: Boolean
        get() = p.getBoolean("pl_gDouble", true)
        set(v) = p.edit().putBoolean("pl_gDouble", v).apply()
    var gesturePinch: Boolean
        get() = p.getBoolean("pl_gPinch", true)
        set(v) = p.edit().putBoolean("pl_gPinch", v).apply()
    var gesturePan: Boolean
        get() = p.getBoolean("pl_gPan", true)
        set(v) = p.edit().putBoolean("pl_gPan", v).apply()
    /** 0.6 = calm, 1.0 = normal, 1.6 = very responsive. */
    var gestureSensitivity: Float
        get() = p.getFloat("pl_gSens", 1f).coerceIn(0.6f, 1.6f)
        set(v) = p.edit().putFloat("pl_gSens", v.coerceIn(0.6f, 1.6f)).apply()

    /** Screen orientation while the player is open (phones/tablets). */
    var playerOrientation: String
        get() = p.getString("pl_orient", OrientationPref.Auto.name) ?: OrientationPref.Auto.name
        set(v) = p.edit().putString("pl_orient", v).apply()

    /** What Up/Down does on a TV remote while the controls are hidden. */
    var tvUpDown: String
        get() = g.getString("pl_tvUpDown", UpDownAction.Volume.name) ?: UpDownAction.Volume.name
        set(v) = g.edit().putString("pl_tvUpDown", v).apply()

    /* ---------- external subtitle remembered per title ---------- */
    private fun subKey(mediaKey: String) = "pl_extSub_" + mediaKey.hashCode()

    fun externalSubtitle(mediaKey: String): ExternalSubtitle? {
        val k = subKey(mediaKey)
        val path = p.getString(k, null) ?: return null
        if (!java.io.File(path).exists()) { clearExternalSubtitle(mediaKey); return null }
        return ExternalSubtitle(path, p.getString("${k}_name", "ترجمة خارجية") ?: "ترجمة خارجية", p.getString("${k}_enc", "auto") ?: "auto")
    }

    fun setExternalSubtitle(mediaKey: String, sub: ExternalSubtitle) {
        val k = subKey(mediaKey)
        p.edit().putString(k, sub.path).putString("${k}_name", sub.name).putString("${k}_enc", sub.encoding).apply()
    }

    fun clearExternalSubtitle(mediaKey: String) {
        val k = subKey(mediaKey)
        val path = p.getString(k, null)
        p.edit().remove(k).remove("${k}_name").remove("${k}_enc").apply()
        if (!path.isNullOrBlank()) runCatching { java.io.File(path).delete() }
    }

    var catchupOffsetHours: Int
        get() = g.getInt("catchupOffset", 0)
        set(v) = g.edit().putInt("catchupOffset", v).apply()
    var tmdbKey: String
        get() = g.getString("tmdbKey", "") ?: ""
        set(v) = g.edit().putString("tmdbKey", v.trim()).apply()
    var filterWords: String
        get() = g.getString("filterWords", "") ?: ""
        set(v) = g.edit().putString("filterWords", v).apply()
    val filterList: List<String> get() = filterWords.split(',', '،', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** Warm the manifest of the neighbouring channels (HLS only) so zapping starts faster. Off by default: some providers limit connections. */
    var prefetchNext: Boolean
        get() = g.getBoolean("prefetchNext", false)
        set(v) = g.edit().putBoolean("prefetchNext", v).apply()

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
        val o = JSONObject(g.getString("epgOverrides", "{}") ?: "{}")
        val m = HashMap<String, String>()
        for (k in o.keys()) m[k] = o.optString(k)
        return m
    }
    fun setEpgOverride(channelUrl: String, xmlId: String?) {
        val o = JSONObject(g.getString("epgOverrides", "{}") ?: "{}")
        if (xmlId.isNullOrBlank()) o.remove(channelUrl) else o.put(channelUrl, xmlId)
        g.edit().putString("epgOverrides", o.toString()).apply()
    }

    /* ---------- parental control ---------- */
    var parentalLevel: Maturity
        get() = if (currentProfile.kids) Maturity.Kids else runCatching { Maturity.valueOf(p.getString("parentalLevel", "Teen")!!) }.getOrDefault(Maturity.Teen)
        set(v) = p.edit().putString("parentalLevel", v.name).apply()
    var hideLocked: Boolean
        get() = p.getBoolean("hideLocked", false)
        set(v) = p.edit().putBoolean("hideLocked", v).apply()
    val hasPin: Boolean get() = !g.getString("pinHash", "").isNullOrEmpty()

    private fun hash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    fun setPin(pin: String) {
        val salt = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        g.edit().putString("pinSalt", salt).putString("pinHash", hash(pin, salt)).apply()
    }
    fun clearPin() { g.edit().remove("pinSalt").remove("pinHash").apply() }
    fun checkPin(pin: String): Boolean {
        val salt = g.getString("pinSalt", "") ?: ""
        val h = g.getString("pinHash", "") ?: ""
        return h.isNotEmpty() && hash(pin, salt) == h
    }

    /* ---------- daily watch limit (per profile) ---------- */
    var limitMinutes: Int
        get() = p.getInt("limitMin", 0)
        set(v) = p.edit().putInt("limitMin", v).apply()

    private fun today() = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
    fun watchedSeconds(): Int = p.getInt("watched_${today()}", 0)
    fun addWatched(sec: Int) = p.edit().putInt("watched_${today()}", watchedSeconds() + sec).apply()

    /* ---------- learned intro end (ms) per series ---------- */
    fun introEnd(seriesKey: String): Long = p.getLong("intro_$seriesKey", 0L)
    fun setIntroEnd(seriesKey: String, ms: Long) = p.edit().putLong("intro_$seriesKey", ms).apply()

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
            val o = JSONObject(g.getString("health", "{}"))
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
        g.edit().putString("health", o.toString()).apply()
    }

    fun clearHealth() { healthMap().clear(); saveHealth() }

    /** (healthy, tested): Ok and Slow count as working. */
    fun healthStats(): Pair<Int, Int> {
        val known = healthMap().values.filter { it.state != Health.Unknown }
        return known.count { it.state == Health.Ok || it.state == Health.Slow } to known.size
    }

    /* ---------- recordings ---------- */
    fun recordings(): List<Recording> {
        val out = ArrayList<Recording>()
        runCatching {
            val a = JSONArray(g.getString("recordings", "[]"))
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
        g.edit().putString("recordings", a.toString()).apply()
    }

    /* ---------- reminders ---------- */
    fun reminders(): List<Reminder> {
        val out = ArrayList<Reminder>()
        runCatching {
            val a = JSONArray(g.getString("reminders", "[]"))
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
        g.edit().putString("reminders", a.toString()).apply()
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

    /* ---------- playback problems report ---------- */
    /** Streams with the most recorded failures, worst first. */
    fun worstHealth(n: Int): List<Pair<String, HealthRecord>> =
        healthMap().entries.filter { it.value.failures > 0 || it.value.state == Health.Dead }
            .sortedByDescending { it.value.failures }.take(n).map { it.key to it.value }

    /* ---------- backup (favorites + preferences, never the PIN or server credentials) ---------- */
    fun exportBackup(): String {
        val o = JSONObject().put("v", 1)
        o.put("fav", JSONArray(favorites.toList()))
        o.put("pinnedGroups", JSONArray(pinnedGroups.toList()))
        o.put("hiddenGroups", JSONArray(hiddenGroups.toList()))
        o.put("hiddenChannels", JSONArray(hiddenChannels.toList()))
        o.put("filterWords", filterWords)
        o.put("audioLang", prefAudioLang)
        o.put("subLang", prefSubLang)
        o.put("startSection", startSection)
        val cg = JSONObject()
        customGroups().forEach { (k, v) -> cg.put(k, JSONArray(v)) }
        o.put("customGroups", cg)
        return o.toString()
    }

    /** Merges a backup produced by [exportBackup]. Returns false when the text is not a valid backup. */
    fun importBackup(text: String): Boolean = runCatching {
        val o = JSONObject(text)
        if (o.optInt("v", 0) != 1) return false
        fun set(k: String): Set<String> {
            val a = o.optJSONArray(k) ?: return emptySet()
            return (0 until a.length()).map { a.getString(it) }.toSet()
        }
        favorites = favorites + set("fav")
        pinnedGroups = pinnedGroups + set("pinnedGroups")
        hiddenGroups = hiddenGroups + set("hiddenGroups")
        hiddenChannels = hiddenChannels + set("hiddenChannels")
        if (o.has("filterWords")) filterWords = o.getString("filterWords")
        if (o.has("audioLang")) prefAudioLang = o.getString("audioLang")
        if (o.has("subLang")) prefSubLang = o.getString("subLang")
        o.optString("startSection").takeIf { s -> Tab.entries.any { it.name == s } }?.let { startSection = it }
        val cg = o.optJSONObject("customGroups")
        if (cg != null) {
            val merged = LinkedHashMap(customGroups())
            for (k in cg.keys()) {
                val a = cg.getJSONArray(k)
                merged[k] = ((merged[k] ?: emptyList()) + (0 until a.length()).map { a.getString(it) }).distinct()
            }
            saveCustomGroups(merged)
        }
        true
    }.getOrDefault(false)
}

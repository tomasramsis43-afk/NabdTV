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

    // profiles() / parentalLevel are read once PER CHANNEL when lists are filtered (tens of thousands of times): parse only when the stored text changed.
    @Volatile private var profBox: Pair<String, List<Profile>>? = null   // one object: readers on other threads never see a half-updated pair

    fun profiles(): List<Profile> {
        val raw = g.getString("profiles", "[]") ?: "[]"
        profBox?.let { if (it.first == raw) return it.second }
        val out = ArrayList<Profile>()
        runCatching {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) a.getJSONObject(i).let { out += Profile(it.getString("id"), it.optString("n"), it.optBoolean("k")) }
        }
        if (out.none { it.id == "main" }) out.add(0, Profile("main", "الرئيسي", false))
        profBox = raw to out
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
        saveProfiles(profiles() + Profile(id, name.trim().ifEmpty { S(R.string.x_profile_default) }, kids))
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
    var accentIdx: Int
        get() = g.getInt("accentIdx", 0)
        set(v) = g.edit().putInt("accentIdx", v).apply()
    var amoled: Boolean
        get() = g.getBoolean("amoled", false)
        set(v) = g.edit().putBoolean("amoled", v).apply()
    var darkMode: Boolean
        get() = g.getBoolean("darkMode", true)
        set(v) = g.edit().putBoolean("darkMode", v).apply()
    var hideDead: Boolean
        get() = g.getBoolean("hideDead", true)
        set(v) = g.edit().putBoolean("hideDead", v).apply()
    /** When the lists were last refreshed from the provider (drives the quiet background refresh). */
    var lastSyncAt: Long
        get() = g.getLong("lastSyncAt", 0L)
        set(v) = g.edit().putLong("lastSyncAt", v).apply()

    /** Option: hide the name written under movie/series posters (live channels keep their names). */
    var hidePosterTitles: Boolean
        get() = g.getBoolean("hidePosterTitles", false)
        set(v) = g.edit().putBoolean("hidePosterTitles", v).apply()
    /** The home poster changes by itself while nobody touches the screen. */
    var heroRotate: Boolean
        get() = g.getBoolean("heroRotate", true)
        set(v) = g.edit().putBoolean("heroRotate", v).apply()

    var autoRefresh: Boolean
        get() = g.getBoolean("autoRefresh", true)
        set(v) = g.edit().putBoolean("autoRefresh", v).apply()
    /** Notify when a favourite series gets new episodes (checked by the background refresh). */
    var notifyNewEpisodes: Boolean
        get() = g.getBoolean("notifyEps", true)
        set(v) = g.edit().putBoolean("notifyEps", v).apply()
    fun knownEpisodeCount(seriesUrl: String): Int = g.getInt("epc_$seriesUrl", 0)
    fun setKnownEpisodeCount(seriesUrl: String, n: Int) = g.edit().putInt("epc_$seriesUrl", n).apply()

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

    /** Show verified official title logos (Wikidata/Commons, no key). Off = plain titles only, no logo downloads. */
    var titleLogos: Boolean
        get() = p.getBoolean("ui_titleLogos", true)
        set(v) = p.edit().putBoolean("ui_titleLogos", v).apply()

    var autoNext: Boolean
        get() = p.getBoolean("pl_autoNext", true)
        set(v) = p.edit().putBoolean("pl_autoNext", v).apply()

    /** Jump over a detected intro / recap by itself (AniSkip / TheIntroDB windows). */
    var autoSkip: Boolean
        get() = p.getBoolean("pl_autoSkip", false)
        set(v) = p.edit().putBoolean("pl_autoSkip", v).apply()

    /** Jump over detected end credits by itself (starts the next episode right away). */
    var autoSkipCredits: Boolean
        get() = p.getBoolean("pl_autoSkipCredits", false)
        set(v) = p.edit().putBoolean("pl_autoSkipCredits", v).apply()

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

    /** FFmpeg software audio decoder (AC3 / DTS ...). Off by default: on some devices it produced a loud whistle. */
    var softAudio: Boolean
        get() = g.getBoolean("pl_softAudio", false)
        set(v) = g.edit().putBoolean("pl_softAudio", v).apply()

    /** Frame-rate matching: "off", "seamless" (system default behaviour) or "switch" (also changes the display mode on TVs). */
    var afrMode: String
        get() = p.getString("pl_afr", "seamless") ?: "seamless"
        set(v) = p.edit().putString("pl_afr", v).apply()

    /** Shows one entry per channel when the list holds several sources of it (HD / FHD / SD); the healthiest source is kept. */
    var mergeDuplicates: Boolean
        get() = p.getBoolean("mergeDup", false)
        set(v) = p.edit().putBoolean("mergeDup", v).apply()

    /** Shows one card per movie / series when the library holds several copies of it (other sources, qualities, languages). */
    var mergeVodDuplicates: Boolean
        get() = p.getBoolean("mergeVod", true)
        set(v) = p.edit().putBoolean("mergeVod", v).apply()

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
        return ExternalSubtitle(path, p.getString("${k}_name", null) ?: S(R.string.x_sub_external), p.getString("${k}_enc", "auto") ?: "auto")
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
    /** Optional Sentry DSN: when set, the last crash log is sent there once, at the next launch. Empty = never sent anywhere. */
    /** Anonymous device count (see [UsageCount]); on by default, can be switched off in Settings. */
    var usageCount: Boolean
        get() = g.getBoolean("usageCount", true)
        set(v) = g.edit().putBoolean("usageCount", v).apply()
    var countedInstall: Boolean
        get() = g.getBoolean("countedInstall", false)
        set(v) = g.edit().putBoolean("countedInstall", v).apply()
    var countedWeek: String
        get() = g.getString("countedWeek", "") ?: ""
        set(v) = g.edit().putString("countedWeek", v).apply()

    var sentryDsn: String
        get() = g.getString("sentryDsn", "") ?: ""
        set(v) = g.edit().putString("sentryDsn", v.trim()).apply()

    /* Trakt.tv sync: the user's own Trakt API app + the tokens obtained by linking. Secrets are stored encrypted. */
    var traktId: String
        get() = g.getString("traktId", "") ?: ""
        set(v) = g.edit().putString("traktId", v.trim()).apply()
    var traktSecret: String
        get() = Secret.dec(g.getString("traktSecret", "") ?: "")
        set(v) = g.edit().putString("traktSecret", Secret.enc(v.trim())).apply()
    var traktAccess: String
        get() = Secret.dec(g.getString("traktAccess", "") ?: "")
        set(v) = g.edit().putString("traktAccess", Secret.enc(v)).apply()
    var traktRefresh: String
        get() = Secret.dec(g.getString("traktRefresh", "") ?: "")
        set(v) = g.edit().putString("traktRefresh", Secret.enc(v)).apply()
    var traktExpiresAt: Long
        get() = g.getLong("traktExpires", 0L)
        set(v) = g.edit().putLong("traktExpires", v).apply()

    /* OpenSubtitles.com (online subtitle search): the user's own API key + account. */
    var osKey: String
        get() = Secret.dec(g.getString("osKey", "") ?: "")   // legacy plain values still read fine, and are re-saved encrypted on next edit
        set(v) = g.edit().putString("osKey", Secret.enc(v.trim())).apply()
    var osUser: String
        get() = g.getString("osUser", "") ?: ""
        set(v) = g.edit().putString("osUser", v.trim()).apply()
    var osPass: String
        get() = Secret.dec(g.getString("osPass", "") ?: "")
        set(v) = g.edit().putString("osPass", Secret.enc(v.trim())).apply()
    /** Download and parse the TV guide (EPG). Off = no guide data; saves battery / heat if you only watch movies and series. */
    var epgEnabled: Boolean
        get() = g.getBoolean("epgEnabled", true)
        set(v) = g.edit().putBoolean("epgEnabled", v).apply()
    var tmdbKey: String
        get() = Secret.dec(g.getString("tmdbKey", "") ?: "")
        set(v) = g.edit().putString("tmdbKey", Secret.enc(v.trim())).apply()
    var filterWords: String
        get() = g.getString("filterWords", "") ?: ""
        set(v) = g.edit().putString("filterWords", v).apply()
    val filterList: List<String> get() = filterWords.split(',', '،', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** Warm the manifest of the neighbouring channels (HLS only) so zapping starts faster. Off by default: some providers limit connections. */
    var prefetchNext: Boolean
        get() = g.getBoolean("prefetchNext", false)
        set(v) = g.edit().putBoolean("prefetchNext", v).apply()

    /* ---------- string sets ---------- */
    private val setCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()   // read from background threads too
    private fun strSet(k: String): Set<String> = setCache.getOrPut(k) { p.getStringSet(k, emptySet())!!.toSet() }
    private fun putStrSet(k: String, v: Set<String>) { setCache[k] = v.toSet(); p.edit().putStringSet(k, v).apply() }

    /** "Watch later": a set of "addedAtMillis|url" entries (sets are unordered); [VmLogic.parseWatchLater] gives newest first. */
    val watchLater: List<String> get() = VmLogic.parseWatchLater(strSet("later"))
    fun addWatchLater(url: String, now: Long = System.currentTimeMillis()) {
        putStrSet("later", strSet("later").filterNot { it.substringAfter('|') == url }.toSet() + "$now|$url")
    }
    fun removeWatchLater(url: String) { putStrSet("later", strSet("later").filterNot { it.substringAfter('|') == url }.toSet()) }

    var favorites: Set<String>
        get() = strSet("fav")
        set(v) = putStrSet("fav", v)
    /** Thumbs up / thumbs down per title, per profile; they steer the recommendations. */
    var liked: Set<String>
        get() = strSet("liked")
        set(v) = putStrSet("liked", v)
    var disliked: Set<String>
        get() = strSet("disliked")
        set(v) = putStrSet("disliked", v)
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
        get() = if (currentProfile.kids) Maturity.Kids else levelOf(p.getString("parentalLevel", "Teen") ?: "Teen")
        set(v) = p.edit().putString("parentalLevel", v.name).apply()
    @Volatile private var levelBox: Pair<String, Maturity>? = null
    private fun levelOf(raw: String): Maturity {
        levelBox?.let { if (it.first == raw) return it.second }
        val m = runCatching { Maturity.valueOf(raw) }.getOrDefault(Maturity.Teen)
        levelBox = raw to m
        return m
    }
    var hideLocked: Boolean
        get() = p.getBoolean("hideLocked", false)
        set(v) = p.edit().putBoolean("hideLocked", v).apply()
    val hasPin: Boolean get() = !g.getString("pinHash2", "").isNullOrEmpty() || !g.getString("pinHash", "").isNullOrEmpty()

    /** Legacy format (single SHA-256 round); still accepted so existing PINs keep working, then upgraded. */
    private fun legacyHash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    // PBKDF2-HMAC-SHA256 needs API 26; older devices use HMAC-SHA1. The algorithm is stored with the hash.
    private fun pbkdfAlgo() = if (android.os.Build.VERSION.SDK_INT >= 26) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val algo = pbkdfAlgo()
        // The digest is also wrapped with the Android Keystore key, so a copied prefs file alone cannot be brute-forced.
        val h = Secret.enc(PinHash.hash(pin, salt, PinHash.ITERATIONS, algo))
        g.edit().putString("pinSalt", salt).putString("pinHash2", h)
            .putString("pinAlgo", algo).putInt("pinIter", PinHash.ITERATIONS).remove("pinHash").apply()
    }
    fun clearPin() {
        g.edit().remove("pinSalt").remove("pinHash").remove("pinHash2").remove("pinAlgo").remove("pinIter").apply()
    }
    fun checkPin(pin: String): Boolean {
        val salt = g.getString("pinSalt", "") ?: ""
        val h2 = g.getString("pinHash2", "") ?: ""
        if (h2.isNotEmpty()) {
            val want = Secret.dec(h2)
            if (want.isEmpty()) return false
            val algo = g.getString("pinAlgo", null) ?: pbkdfAlgo()
            val iter = g.getInt("pinIter", PinHash.ITERATIONS)
            return runCatching { PinHash.same(PinHash.hash(pin, salt, iter, algo), want) }.getOrDefault(false)
        }
        val h = g.getString("pinHash", "") ?: ""
        val ok = h.isNotEmpty() && PinHash.same(legacyHash(pin, salt), h)
        if (ok) setPin(pin)   // upgrade a legacy hash on the first successful entry
        return ok
    }

    /** PIN-attempt lockout state, kept on disk so restarting the app does not reset it. */
    val pinMisses: Int get() = g.getInt("pinMisses", 0)
    val pinBlockedUntil: Long get() = g.getLong("pinBlockedUntil", 0L)
    fun savePinThrottle(misses: Int, blockedUntil: Long) {
        g.edit().putInt("pinMisses", misses).putLong("pinBlockedUntil", blockedUntil).apply()
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

    /* ---------- audio / subtitle language chosen per series (applied to its next episodes) ---------- */
    fun seriesAudioLang(seriesKey: String): String = p.getString("sAud_$seriesKey", "") ?: ""
    fun setSeriesAudioLang(seriesKey: String, lang: String) = p.edit().putString("sAud_$seriesKey", lang).apply()
    /** "" = no choice made, "off" = subtitles switched off, otherwise a language code. */
    fun seriesSubLang(seriesKey: String): String = p.getString("sSub_$seriesKey", "") ?: ""
    fun setSeriesSubLang(seriesKey: String, lang: String) = p.edit().putString("sSub_$seriesKey", lang).apply()

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

    fun clearHistory() { hist().clear(); saveHist(); epMetas().clear(); saveEpMetas(); cursors().clear(); saveCursors() }

    /** Forgets the watch progress of these items (episode / movie URLs). */
    fun removeHistory(urls: Collection<String>) {
        urls.forEach { hist().remove(it); epMetas().remove(it) }
        saveHist(); saveEpMetas()
    }

    /** Removes a whole series from Continue watching: all its episodes' progress and its "current episode" marker. */
    fun removeSeriesProgress(seriesUrl: String, episodeUrls: Collection<String>) {
        val metaKeys = epMetas().filterValues { it.seriesUrl == seriesUrl }.keys
        (episodeUrls + metaKeys).forEach { hist().remove(it); epMetas().remove(it) }
        cursors().remove(seriesUrl)
        saveHist(); saveEpMetas(); saveCursors()
    }

    /* ---------- episode metadata + current episode per series (Continue watching) ---------- */
    private var epMetaCache: LinkedHashMap<String, EpMeta>? = null

    private fun epMetas(): LinkedHashMap<String, EpMeta> {
        epMetaCache?.let { return it }
        val m = LinkedHashMap<String, EpMeta>()
        runCatching {
            val o = JSONObject(p.getString("epmeta", "{}"))
            for (k in o.keys()) {
                val j = o.getJSONObject(k)
                m[k] = EpMeta(j.optString("s"), j.optString("n"), j.optInt("se"), j.optInt("e"), j.optString("t"), j.optString("i").ifEmpty { null })
            }
        }
        epMetaCache = m
        return m
    }

    private fun saveEpMetas() {
        val o = JSONObject()
        epMetas().forEach { (k, e) ->
            o.put(k, JSONObject().put("s", e.seriesUrl).put("n", e.seriesName).put("se", e.season).put("e", e.num).put("t", e.title).put("i", e.image ?: ""))
        }
        p.edit().putString("epmeta", o.toString()).apply()
    }

    fun epMeta(url: String): EpMeta? = epMetas()[url]

    fun saveEpMeta(url: String, m: EpMeta) {
        val all = epMetas()
        all.remove(url); all[url] = m
        while (all.size > 600) all.remove(all.keys.first())
        saveEpMetas()
    }

    private var cursorCache: LinkedHashMap<String, Pair<String, Long>>? = null

    private fun cursors(): LinkedHashMap<String, Pair<String, Long>> {
        cursorCache?.let { return it }
        val m = LinkedHashMap<String, Pair<String, Long>>()
        runCatching {
            val o = JSONObject(p.getString("contcursor", "{}"))
            for (k in o.keys()) { val j = o.getJSONObject(k); m[k] = j.optString("u") to j.optLong("t") }
        }
        cursorCache = m
        return m
    }

    private fun saveCursors() {
        val o = JSONObject()
        cursors().forEach { (k, v) -> o.put(k, JSONObject().put("u", v.first).put("t", v.second)) }
        p.edit().putString("contcursor", o.toString()).apply()
    }

    /** The episode Continue watching currently shows for a series (last played, or picked with the prev/next arrows). */
    fun contCursor(seriesUrl: String): Pair<String, Long>? = cursors()[seriesUrl]

    fun setContCursor(seriesUrl: String, episodeUrl: String) {
        val c = cursors(); c.remove(seriesUrl); c[seriesUrl] = episodeUrl to System.currentTimeMillis()
        while (c.size > 300) c.remove(c.keys.first())
        saveCursors()
    }

    /* ---------- stream health ---------- */
    @Volatile private var healthCache: java.util.concurrent.ConcurrentHashMap<String, HealthRecord>? = null

    private fun healthMap(): java.util.concurrent.ConcurrentHashMap<String, HealthRecord> {
        healthCache?.let { return it }
        val m = java.util.concurrent.ConcurrentHashMap<String, HealthRecord>()
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

    /* ---------- backup (favorites, watch progress + preferences, never the PIN or server credentials) ---------- */
    fun exportBackup(): String {
        val o = JSONObject().put("v", 2)
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
        // watch progress, so "continue watching" follows the user to another device
        val hs = JSONArray()
        hist().values.forEach { h ->
            hs.put(JSONObject().put("u", h.url).put("n", h.name).put("l", h.logo ?: "").put("g", h.group).put("k", h.kind.name)
                .put("p", h.position).put("d", h.duration).put("t", h.last).put("c", h.count))
        }
        o.put("history", hs)
        // per-series learned intro end and chosen audio / subtitle language
        val sp = JSONObject()
        p.all.forEach { (k, v) -> if (v != null && (k.startsWith("intro_") || k.startsWith("sAud_") || k.startsWith("sSub_"))) sp.put(k, v) }
        o.put("seriesPrefs", sp)
        return o.toString()
    }

    /** Merges a backup produced by [exportBackup]. Returns false when the text is not a valid backup. */
    fun importBackup(text: String): Boolean = runCatching {
        val o = JSONObject(text)
        if (o.optInt("v", 0) !in 1..2) return false
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
        o.optJSONArray("history")?.let { a ->
            val m = hist()
            for (i in 0 until a.length()) {
                val j = a.getJSONObject(i)
                val url = j.getString("u")
                val kind = runCatching { Kind.valueOf(j.getString("k")) }.getOrDefault(Kind.Movie)
                val e = HistoryEntry(url, j.getString("n"), j.optString("l").ifBlank { null }, j.optString("g"), kind,
                    j.optLong("p"), j.optLong("d"), j.optLong("t"), j.optInt("c"))
                val old = m[url]
                if (old == null || e.last > old.last) m[url] = e   // keep the most recent progress
            }
            saveHist()
        }
        o.optJSONObject("seriesPrefs")?.let { sp ->
            val ed = p.edit()
            for (k in sp.keys()) {
                if (!(k.startsWith("intro_") || k.startsWith("sAud_") || k.startsWith("sSub_"))) continue
                when (val v = sp.get(k)) { is Number -> ed.putLong(k, v.toLong()); is String -> ed.putString(k, v) }
            }
            ed.apply()
        }
        true
    }.getOrDefault(false)
}

package com.nabd.tv

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * The next episode of a running series.
 * [exact] = AniList gave a real air time (anime), so the countdown can tick down to the second.
 * Otherwise (TMDB) only the air DATE is known: [atMs] is local midnight of that day and the UI shows days, not hours.
 */
data class NextAir(val episode: Int, val season: Int?, val atMs: Long, val exact: Boolean, val source: String)

/**
 * Finds when the next episode of a series comes out.
 *  1. AniList (no key): exact air time, only for anime that is currently airing and whose title matches exactly.
 *  2. TMDB (needs the user's key): `next_episode_to_air` of the exact title match; date only, no time of day.
 * Every failure returns null, so the detail page simply shows nothing.
 */
object NextAiring {
    private const val UA = "NabdTV v2"
    private val JSON = "application/json".toMediaType()
    private const val HIT_TTL = 30L * 60_000L
    private const val MISS_TTL = 3L * 3_600_000L

    private val cache = ConcurrentHashMap<String, Pair<Long, NextAir?>>()

    suspend fun find(http: OkHttpClient, tmdbKey: String, rawTitle: String): NextAir? = withContext(Dispatchers.IO) {
        runCatching {
            val (title, year) = LogoMatch.clean(rawTitle)
            if (title.isBlank()) return@runCatching null
            val ck = LogoMatch.norm(title)
            val now = System.currentTimeMillis()
            cache[ck]?.let { (t, v) -> if (now - t < (if (v == null) MISS_TTL else HIT_TTL)) return@runCatching v }
            val found = (if (LogoMatch.hasArabic(title)) null else fromAniList(http, AniSkip.cleanName(title), now))
                ?: fromTmdb(http, tmdbKey, rawTitle, year, now)
            cache[ck] = now to found   // network failures throw before this line, so they are never cached as "nothing"
            found
        }.getOrNull()
    }

    /* ---------------- AniList: exact air time ---------------- */

    private const val ANI_QUERY =
        "query(\$s:String){Page(perPage:6){media(search:\$s,type:ANIME,status:RELEASING,format_in:[TV,TV_SHORT,ONA],sort:SEARCH_MATCH)" +
            "{title{romaji english} synonyms nextAiringEpisode{airingAt episode}}}}"

    private fun fromAniList(http: OkHttpClient, base: String, now: Long): NextAir? {
        if (base.length < 2) return null
        val body = JSONObject().put("query", ANI_QUERY).put("variables", JSONObject().put("s", base)).toString().toRequestBody(JSON)
        val req = Request.Builder().url("https://graphql.anilist.co").header("User-Agent", UA).header("Accept", "application/json").post(body).build()
        val text = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("AniList HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        return parseAniList(text, base, now)
    }

    private val SEASON_REST = Regex("(season \\d+|\\d+(st|nd|rd|th) season|part \\d+|\\d+|final season|the final season)")

    /** Title equals the library title, or equals it plus a season marker ("... season 2"). Anything else ("... daima") is a different show. */
    internal fun titleMatches(want: String, titles: List<String>): Boolean = titles.any { t ->
        t == want || (t.startsWith("$want ") && SEASON_REST.matches(t.removePrefix(want).trim()))
    }

    internal fun parseAniList(body: String, baseTitle: String, nowMs: Long): NextAir? {
        val media = JSONObject(body).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return null
        val want = AniSkip.norm(baseTitle)
        if (want.isEmpty()) return null
        for (i in 0 until media.length()) {
            val m = media.getJSONObject(i)
            val titles = buildList<String> {
                m.optJSONObject("title")?.let { t -> add(t.optString("romaji")); add(t.optString("english")) }
                m.optJSONArray("synonyms")?.let { a -> for (j in 0 until a.length()) add(a.optString(j)) }
            }.map { AniSkip.norm(it) }.filter { it.isNotEmpty() && it != "null" }
            if (!titleMatches(want, titles)) continue
            val nx = m.optJSONObject("nextAiringEpisode") ?: continue
            val at = nx.optLong("airingAt", 0L) * 1000L
            val ep = nx.optInt("episode", 0)
            if (at <= 0L || ep <= 0 || at < nowMs - 6L * 3_600_000L) continue
            return NextAir(ep, null, at, true, "anilist")
        }
        return null
    }

    /* ---------------- TMDB: air date only ---------------- */

    private fun fromTmdb(http: OkHttpClient, key: String, rawTitle: String, year: Int?, now: Long): NextAir? {
        if (key.isBlank()) return null
        val id = Artwork.hit(rawTitle, Kind.Series, year)?.optInt("id", 0)?.takeIf { it > 0 } ?: return null
        val url = "https://api.themoviedb.org/3/tv/$id?api_key=${Uri.encode(key)}&language=en"
        val text = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("TMDB HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        return parseTmdb(text, now)
    }

    internal fun parseTmdb(body: String, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): NextAir? {
        val o = JSONObject(body)
        val status = o.optString("status")
        if (status == "Ended" || status == "Canceled") return null
        val n = o.optJSONObject("next_episode_to_air") ?: return null
        val ep = n.optInt("episode_number", 0)
        if (ep <= 0) return null
        val at = midnightOf(n.optString("air_date"), tz) ?: return null
        if (dayDiff(at, nowMs, tz) < 0) return null   // stale: the date already passed
        return NextAir(ep, n.optInt("season_number", 0).takeIf { it > 0 }, at, false, "tmdb")
    }

    /** Local midnight (in [tz]) of a "yyyy-MM-dd" date; null for anything else. */
    internal fun midnightOf(date: String, tz: TimeZone = TimeZone.getDefault()): Long? {
        if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(date)) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz; isLenient = false }.parse(date)?.time
        }.getOrNull()
    }

    /** Whole calendar days from today (in [tz]) to the day that starts at [targetMidnightMs]; 0 = today, 1 = tomorrow. */
    internal fun dayDiff(targetMidnightMs: Long, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): Int {
        val today = Calendar.getInstance(tz).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return ((targetMidnightMs - today) / 86_400_000.0).roundToInt()   // rounding absorbs a 23 / 25 hour day around DST
    }
}

/** "Next: episode 5" + a live countdown. Exact times tick every second in the last hour; date-only stays at day level. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NextAirChips(n: NextAir) {
    var now by remember(n) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(n) {
        while (true) {
            now = System.currentTimeMillis()
            val left = n.atMs - now
            delay(if (n.exact && left in 1L..3_600_000L) 1_000L else if (n.exact) 15_000L else 60_000L)
        }
    }
    val label = if (n.season != null) tr(R.string.next_ep_label_se, n.episode, n.season) else tr(R.string.next_ep_label_ep, n.episode)
    val countdown = if (n.exact) {
        val left = n.atMs - now
        if (left <= 0L) tr(R.string.next_ep_out_now) else {
            val s = left / 1000L
            val d = (s / 86_400L).toInt(); val h = ((s % 86_400L) / 3_600L).toInt(); val m = ((s % 3_600L) / 60L).toInt(); val sec = (s % 60L).toInt()
            if (d > 0) tr(R.string.next_ep_in_days_hours, d, h) else tr(R.string.next_ep_in_hms, h, m, sec)
        }
    } else {
        when (val days = NextAiring.dayDiff(n.atMs, now)) {
            in Int.MIN_VALUE..0 -> tr(R.string.next_ep_today)
            1 -> tr(R.string.next_ep_tomorrow)
            else -> tr(R.string.next_ep_in_days, days)
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MetaChip(label, fg = Color(0xFFB9F0C3), bg = Color(0x3329C06A))
        MetaChip("⏰ $countdown", fg = Color(0xFFFFD27A), bg = Color(0x33FFB74D))
    }
}

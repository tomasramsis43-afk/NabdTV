package com.nabd.tv

/** Content kinds shown in the app. */
enum class Kind { Live, Movie, Series }

/** Auto-detected genre of a channel / title. */
enum class Genre(val label: String) {
    News("أخبار"), Sports("رياضة"), Kids("أطفال"), Movies("أفلام"), Drama("دراما ومسلسلات"),
    Documentary("وثائقي"), Music("موسيقى"), Religion("ديني"), Entertainment("ترفيه"),
    Action("أكشن"), Comedy("كوميديا"), Adult("للبالغين"), General("عام")
}

/** Maturity level used by parental control. Higher = more restricted content. */
enum class Maturity { Kids, Teen, Adult }

data class Channel(
    val id: Int,
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val tvgId: String? = null,
    val archiveDays: Int = 0,
    val catchupSource: String? = null,
    val number: Int = 0,
    val rating: String? = null,
    val added: Long = 0L          // epoch seconds the provider added it (0 = unknown)
) {
    val hasCatchup: Boolean get() = archiveDays > 0 || !catchupSource.isNullOrBlank()
}

data class Prog(val start: Long, val stop: Long, val title: String, val desc: String = "")

data class Episode(val season: Int, val num: Int, val title: String, val url: String, val plot: String = "", val image: String? = null)

/** Extra metadata for a movie or a series (from Xtream or TMDB). */
data class VodInfo(
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val genre: String = "",
    val rating: String = "",
    val year: String = "",
    val backdrop: String? = null,
    val duration: String = "",
    /** YouTube video id of the trailer (from the provider or TMDB), blank when unknown. */
    val trailer: String = "",
    /** Poster the provider sends with the details (usually sharper than the list thumbnail). */
    val poster: String? = null,
    /** Cast with photos when TMDB is available; otherwise built from the [cast] text without photos. */
    val people: List<Person> = emptyList()
)

data class Person(val name: String, val photo: String? = null, val role: String = "", val id: Int = 0)

data class HistoryEntry(
    val url: String,
    val name: String,
    val logo: String?,
    val group: String,
    val kind: Kind,
    val position: Long,
    val duration: Long,
    val last: Long,
    val count: Int
) {
    val progress: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = duration > 0 && progress > 0.95f
}

enum class Health { Unknown, Ok, Slow, Dead }

data class HealthRecord(val state: Health, val latencyMs: Long, val checked: Long, val failures: Int)

enum class RecStatus { Scheduled, Recording, Done, Failed, Cancelled }

data class Recording(
    val id: String,
    val channelName: String,
    val channelUrl: String,
    val title: String,
    val start: Long,
    val stop: Long,
    val status: RecStatus,
    val file: String? = null
)

data class Reminder(val id: String, val channelName: String, val title: String, val start: Long)

/** A row of content on the home screen. */
/** Clean TMDB artwork for a library title: a wide backdrop and TMDB's own 0-10 rating ("8.4"). */
data class TmdbArt(val image: String, val rating: String?)

data class Shelf(val title: String, val items: List<Channel>, val ranked: Boolean = false, val art: Map<String, TmdbArt> = emptyMap())

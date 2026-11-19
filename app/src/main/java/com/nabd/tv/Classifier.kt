package com.nabd.tv

import java.util.Locale

/** Rule-based, bilingual (Arabic/English) auto-categorization and name normalization. */
object Classifier {

    private val rules: List<Pair<Genre, List<String>>> = listOf(
        Genre.Adult to listOf("xxx", "porn", "adult", "18+", "+18", "erotic", "playboy", "hustler", "للكبار", "للبالغين", "اباحي", "إباحي"),
        Genre.Kids to listOf("kids", "kid", "cartoon", "junior", "toon", "baby", "children", "disney", "nick", "spacetoon",
            "اطفال", "أطفال", "كرتون", "طيور", "سبيستون", "مدبلج"),
        Genre.News to listOf("news", "اخبار", "أخبار", "jazeera", "الجزيرة", "arabiya", "العربية", "cnn", "bbc", "sky news", "france 24", "الحدث"),
        Genre.Sports to listOf("sport", "sports", "bein", "ssc", "football", "soccer", "nba", "ufc", "wwe", "racing", "رياض", "كأس", "دوري", "مباراة"),
        Genre.Religion to listOf("quran", "قران", "قرآن", "islam", "اسلام", "إسلام", "makkah", "مكة", "madina", "المدينة", "sunna", "church", "ديني"),
        Genre.Documentary to listOf("documentary", "docu", "nat geo", "national geographic", "discovery", "history", "animal planet", "وثائق"),
        Genre.Music to listOf("music", "mtv", "radio", "اغاني", "أغاني", "موسيقى", "mazzika", "rotana clip", "clip"),
        Genre.Movies to listOf("movie", "movies", "film", "cinema", "افلام", "أفلام", "سينما", "hbo", "cinema"),
        Genre.Drama to listOf("series", "drama", "مسلسل", "دراما", "turkish", "تركي", "novela", "mbc drama"),
        Genre.Action to listOf("action", "اكشن", "أكشن", "thriller", "crime", "war", "حرب"),
        Genre.Comedy to listOf("comedy", "كوميدي", "مضحك", "sitcom"),
        Genre.Entertainment to listOf("entertain", "ترفيه", "show", "variety", "reality", "برامج")
    )

    private val cache = HashMap<String, Genre>(4096)

    /** Detects a genre using the group (category) first, then the channel name. */
    fun genre(name: String, group: String): Genre {
        val key = "$group|$name"
        cache[key]?.let { return it }
        val g = detect(group.lowercase(Locale.ROOT)) ?: detect(name.lowercase(Locale.ROOT)) ?: Genre.General
        if (cache.size > 20_000) cache.clear()
        cache[key] = g
        return g
    }

    private fun detect(text: String): Genre? {
        for ((g, words) in rules) if (words.any { text.contains(it) }) return g
        return null
    }

    fun genre(c: Channel): Genre = genre(c.name, c.group)

    fun isAdult(c: Channel): Boolean = genre(c) == Genre.Adult ||
        c.rating?.trim()?.let { it == "18" || it.equals("R", true) || it.equals("NC-17", true) || it.equals("TV-MA", true) } == true

    fun maturity(c: Channel): Maturity = when {
        isAdult(c) -> Maturity.Adult
        genre(c) == Genre.Kids -> Maturity.Kids
        else -> Maturity.Teen
    }

    private val quality = Regex("""\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|h264|raw|1080p?|720p?|50fps|60fps)\b""", RegexOption.IGNORE_CASE)
    private val junk = Regex("""[\[\](){}|•·:_\-–—*#]+|\b(ar|arabic|en|vip|plus|backup|premium|tv|channel|قناة)\b""", RegexOption.IGNORE_CASE)
    private val spaces = Regex("""\s+""")

    /** Normalized key used to find alternative sources and to fuzzy-match the guide. */
    fun normalize(name: String): String {
        var s = name.lowercase(Locale.ROOT)
        s = quality.replace(s, " ")
        s = junk.replace(s, " ")
        s = s.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا").replace("ة", "ه").replace("ى", "ي")
        return spaces.replace(s, " ").trim()
    }

    /** Removes user-defined noisy words from category names. */
    fun cleanGroup(group: String, filterWords: List<String>): String {
        var g = group
        for (w in filterWords) if (w.isNotBlank()) g = g.replace(w, "", ignoreCase = true)
        return spaces.replace(g, " ").trim().ifEmpty { group }
    }
}

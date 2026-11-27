package com.nabd.tv

/**
 * Tells Arab-world productions from foreign ones using only what the provider gives (category name, title script).
 * Used so the "Trending worldwide" rows show international movies/series, not Arabic ones.
 */
object Origin {
    private val ARABIC_GROUP = Regex(
        "عرب|مصر|خليج|سعود|كويت|اماراتي|إماراتي|سوري|لبنان|عراق|أردن|اردن|مغرب|جزائر|تونس|فلسطين|يمن|سودان|" +
            "\\b(arabic|arab|arabian|egypt\\w*|gulf|saudi|khaleeji|syrian|lebanese|ar)\\b", RegexOption.IGNORE_CASE)
    private val FOREIGN_GROUP = Regex(
        "أجنب|اجنب|هوليوود|انجليز|إنجليز|تركي|كوري|هندي|اسيوي|آسيوي|مدبلج|مترجم|" +
            "\\b(foreign|english|hollywood|netflix|us|uk|en|korean|turkish|indian|asian|dubbed|subbed|subtitled|international|world)\\b",
        RegexOption.IGNORE_CASE)

    /** "ko" -> "Korean" / "الكورية" in app language [lang]; unknown or free text is returned as it is. */
    fun languageName(code: String, lang: String): String {
        val c = code.trim()
        if (!Regex("[A-Za-z]{2,3}").matches(c)) return c
        val tag = if (c.equals("cn", true)) "yue" else c.lowercase()   // TMDB uses "cn" for Cantonese
        val name = java.util.Locale(tag).getDisplayLanguage(java.util.Locale(lang))
        return if (name.isBlank() || name.equals(tag, true)) c.uppercase() else name.replaceFirstChar { it.uppercase() }
    }

    /** "US" -> "United States" / "الولايات المتحدة" in app language [lang]; unknown or free text is returned as it is. */
    fun countryName(code: String, lang: String): String {
        val c = code.trim()
        if (!Regex("[A-Za-z]{2}").matches(c)) return c
        val name = java.util.Locale("", c.uppercase()).getDisplayCountry(java.util.Locale(lang))
        return if (name.isBlank() || name.equals(c, true)) c.uppercase() else name
    }

    private fun arabicLetters(s: String) = s.count { it in '\u0600'..'\u06FF' }
    private fun latinLetters(s: String) = s.count { it in 'A'..'Z' || it in 'a'..'z' }

    /**
     * Foreign beats Arabic when the category says so ("أفلام أجنبية", "Turkish dubbed"); otherwise an Arabic category
     * or an Arabic-script title means Arabic content. With no signal at all the item counts as foreign.
     */
    fun isArabic(title: String, group: String): Boolean {
        if (FOREIGN_GROUP.containsMatchIn(group)) return false
        if (ARABIC_GROUP.containsMatchIn(group)) return true
        return arabicLetters(title) > latinLetters(title)
    }
}

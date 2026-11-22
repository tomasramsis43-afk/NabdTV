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

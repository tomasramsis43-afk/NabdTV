package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginTest {
    @Test fun arabicCategoriesAreArabic() {
        assertTrue(Origin.isArabic("Whatever", "أفلام عربية"))
        assertTrue(Origin.isArabic("Whatever", "Arabic Series"))
        assertTrue(Origin.isArabic("Whatever", "AR | Movies"))
        assertTrue(Origin.isArabic("Whatever", "مسلسلات مصرية"))
    }

    @Test fun foreignCategoriesWinEvenWithArabicTitles() {
        assertFalse(Origin.isArabic("الرجل العنكبوت", "أفلام أجنبية"))
        assertFalse(Origin.isArabic("حب وانتقام", "مسلسلات تركية مدبلجة"))
        assertFalse(Origin.isArabic("Joker", "Hollywood Movies"))
    }

    @Test fun fallsBackToTitleScriptWhenCategoryIsSilent() {
        assertTrue(Origin.isArabic("باب الحارة", "Series"))
        assertFalse(Origin.isArabic("Breaking Bad", "Series"))
    }

    private fun ch(i: Int, rating: String?, addedDaysAgo: Int?, now: Long) =
        Channel(i, "t$i", "u$i", null, "g", rating = rating, added = if (addedDaysAgo == null) 0L else now - addedDaysAgo * 86_400L)

    @Test fun trendingMixesRatingAndFreshness() {
        val now = 1_800_000_000L
        val l = listOf(
            ch(1, "8.0", 10, now),      // 10.5
            ch(2, "9.0", 800, now),     // 9.0
            ch(3, "7.5", 60, now),      // 9.0
            ch(4, null, 5, now),        // unrated -> dropped
            ch(5, "8.4", null, now)     // 8.4
        )
        val r = VmLogic.trending(l, 10, now).map { it.id }
        assertEquals(4, r.size)
        assertEquals(1, r.first())
        assertEquals(setOf(2, 3), r.subList(1, 3).toSet())
        assertEquals(5, r.last())
        assertFalse(4 in r)
    }
}

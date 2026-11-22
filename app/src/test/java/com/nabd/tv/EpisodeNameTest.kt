package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeNameTest {
    @Test fun parsesArabicLabel() {
        assertEquals(Triple("باب الحارة", 2, 14), VmLogic.parseEpisodeName("باب الحارة \u00B7 \u0645\u0662 \u062D\u0661\u0664".replace("\u0662", "2").replace("\u0661\u0664", "14")))
    }

    @Test fun parsesEnglishLabel() {
        assertEquals(Triple("Breaking Bad", 1, 3), VmLogic.parseEpisodeName("Breaking Bad \u00B7 S01 E03"))
    }

    @Test fun keepsDotsInsideTheSeriesName() {
        assertEquals(Triple("Mr. Robot \u00B7 Uncut", 4, 2), VmLogic.parseEpisodeName("Mr. Robot \u00B7 Uncut \u00B7 S04 E02"))
    }

    @Test fun rejectsNonEpisodeNames() {
        assertNull(VmLogic.parseEpisodeName("Inception"))
        assertNull(VmLogic.parseEpisodeName(" \u00B7 S01 E01"))
    }
}

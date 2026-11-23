package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodFailoverTest {
    @Test fun sameTitleIgnoresQualityTagsAndBrackets() {
        assertTrue(VmLogic.sameTitle("Inception (2010) FHD", "Inception 2010 HD"))
        assertTrue(VmLogic.sameTitle("AR | Dark", "Dark [4K]"))
    }

    @Test fun differentTitlesAreNeverTheSame() {
        assertFalse(VmLogic.sameTitle("Joker", "Joker Folie"))
        assertFalse(VmLogic.sameTitle("", ""))
    }

    @Test fun remakeWithADifferentYearIsRejected() {
        assertFalse(VmLogic.sameTitle("The Lion King (1994)", "The Lion King (2019)"))
    }

    @Test fun oneSidedYearIsNotVerifiable() {
        assertFalse(VmLogic.sameTitle("The Lion King (1994)", "The Lion King"))
        assertTrue(VmLogic.sameTitle("The Lion King", "The Lion King HD"))
    }

    @Test fun findsTheSameEpisodeInAnotherSource() {
        val eps = listOf(Episode(1, 1, "a", "u11"), Episode(1, 2, "b", "u12"), Episode(2, 1, "c", "u21"))
        assertEquals("u21", VmLogic.episodeIn(eps, 2, 1)?.url)
        assertNull(VmLogic.episodeIn(eps, 3, 1))
    }
}

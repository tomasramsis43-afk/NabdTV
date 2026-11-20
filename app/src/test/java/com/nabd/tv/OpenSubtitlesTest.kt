package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenSubtitlesTest {
    @Test fun yearAndQualityTagsAreRemoved() {
        assertEquals("The Matrix", OpenSubtitles.cleanQuery("The Matrix (1999) 1080p BluRay"))
    }

    @Test fun qualityTagsAreCaseInsensitive() {
        assertEquals("Dune", OpenSubtitles.cleanQuery("Dune 2160p WEB-DL x265"))
    }

    @Test fun episodeMarkerIsRemoved() {
        assertEquals("Breaking Bad", OpenSubtitles.cleanQuery("Breaking Bad م1 ح3"))
    }

    @Test fun plainTitleIsUnchanged() {
        assertEquals("Inception", OpenSubtitles.cleanQuery("Inception"))
    }
}

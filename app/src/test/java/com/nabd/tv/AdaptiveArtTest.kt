package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveArtTest {
    @Test fun pickPrefersTheMatchingOrientation() {
        val a = ArtPair("poster", "backdrop")
        assertEquals("backdrop", a.pick(landscape = true))
        assertEquals("poster", a.pick(landscape = false))
    }

    @Test fun pickFallsBackToTheOtherPicture() {
        assertEquals("poster", ArtPair("poster", null).pick(landscape = true))
        assertEquals("backdrop", ArtPair(null, "backdrop").pick(landscape = false))
        assertNull(ArtPair(null, null).pick(landscape = true))
    }

    @Test fun naturalIsFalseWhenOnlyAFallbackExists() {
        assertFalse(ArtPair("poster", null).natural(landscape = true))
        assertTrue(ArtPair("poster", "b").natural(landscape = true))
    }

    @Test fun cardSizedShrinksOnlyTmdbUrls() {
        assertEquals("https://image.tmdb.org/t/p/w780/a.jpg", cardSized("https://image.tmdb.org/t/p/w1280/a.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w500/a.jpg", cardSized("https://image.tmdb.org/t/p/w780/a.jpg"))
        // A provider picture is never touched.
        assertEquals("http://provider.example/p.jpg", cardSized("http://provider.example/p.jpg"))
        assertNull(cardSized(null))
    }
}

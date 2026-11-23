package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AniIntroTest {
    @Test fun packRoundTrip() {
        val l = listOf(
            AniIntro(1_000L, 91_000L, SkipKind.Intro),
            AniIntro(0L, 30_000L, SkipKind.Recap),
            AniIntro(1_320_000L, -1L, SkipKind.Credits)   // credits that run to the end of the media
        )
        assertEquals(l, AniIntro.unpackAll(AniIntro.packAll(l)))
    }

    @Test fun badCacheEntriesAreIgnored() {
        assertNull(AniIntro.unpack("x,1,2"))
        assertNull(AniIntro.unpack("i,1"))
        assertNull(AniIntro.unpack("i,a,b"))
        assertEquals(listOf(AniIntro(1L, 2L, SkipKind.Intro)), AniIntro.unpackAll("i,1,2;garbage"))
    }
}

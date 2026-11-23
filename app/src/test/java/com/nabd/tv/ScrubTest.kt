package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScrubTest {
    @Test fun hidesQueryCredentials() {
        val s = Scrub.clean("GET http://h:80/get.php?username=bob&password=s3cret&type=m3u_plus failed")
        assertFalse(s.contains("bob")); assertFalse(s.contains("s3cret"))
        assertEquals("GET http://h:80/get.php?username=***&password=***&type=m3u_plus failed", s)
    }

    @Test fun hidesTmdbKey() {
        assertEquals("https://api.themoviedb.org/3/search/movie?api_key=***&language=en",
            Scrub.clean("https://api.themoviedb.org/3/search/movie?api_key=abc123&language=en"))
    }

    @Test fun hidesXtreamPathCredentials() {
        assertEquals("http://h/live/***/***/1234.ts", Scrub.clean("http://h/live/bob/s3cret/1234.ts"))
        assertEquals("http://h/movie/***/***/9.mkv", Scrub.clean("http://h/movie/bob/s3cret/9.mkv"))
    }

    @Test fun hidesUserInfo() {
        assertEquals("https://***:***@host/x", Scrub.clean("https://bob:s3cret@host/x"))
    }

    @Test fun leavesPlainTextAlone() {
        val t = "java.lang.NullPointerException at com.nabd.tv.Repo.load(Repo.kt:88)"
        assertEquals(t, Scrub.clean(t))
    }
}

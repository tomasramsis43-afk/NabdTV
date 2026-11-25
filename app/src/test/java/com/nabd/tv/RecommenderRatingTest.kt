package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderRatingTest {
    private fun ch(id: Int, name: String, group: String) = Channel(id, name, "u$id", null, group)
    private val pool = listOf(
        ch(1, "Funny One", "Comedy"), ch(2, "Funny Two", "Comedy"), ch(3, "Scary One", "Horror"), ch(4, "Scary Two", "Horror")
    )

    @Test fun noSignalMeansNoPicks() {
        assertTrue(Recommender.recommend(pool, emptyList(), emptySet(), 10).isEmpty())
    }

    @Test fun likedTitleGivesPicksFromItsCategoryAndIsNotRepeated() {
        val r = Recommender.recommend(pool, emptyList(), emptySet(), 10, liked = setOf("u1"))
        assertFalse("the liked title itself is not recommended", r.any { it.url == "u1" })
        assertEquals("u2", r.first().url)
    }

    @Test fun dislikedTitleIsNeverRecommendedAndItsCategoryRanksLower() {
        val r = Recommender.recommend(pool, emptyList(), setOf("u1"), 10, liked = emptySet(), disliked = setOf("u3"))
        assertFalse(r.any { it.url == "u3" })
        val urls = r.map { it.url }
        if ("u4" in urls && "u2" in urls) assertTrue(urls.indexOf("u2") < urls.indexOf("u4"))
    }
}

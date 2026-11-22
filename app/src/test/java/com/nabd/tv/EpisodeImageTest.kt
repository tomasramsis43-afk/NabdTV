package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeImageTest {
    private fun ep(n: Int, img: String?) = Episode(1, n, "e$n", "u$n", image = img)

    @Test fun keepsAnEpisodesOwnPicture() {
        val l = listOf(ep(1, "a.jpg"), ep(2, "b.jpg"))
        assertEquals("a.jpg", VmLogic.episodeImage(l[0], l, "poster.jpg"))
    }

    @Test fun dropsTheSeriesCoverSentForEveryEpisode() {
        val l = listOf(ep(1, "cover.jpg"), ep(2, "cover.jpg"), ep(3, "cover.jpg"))
        assertNull(VmLogic.episodeImage(l[0], l, null))
    }

    @Test fun dropsAPictureEqualToTheSeriesPoster() {
        val l = listOf(ep(1, "poster.jpg"), ep(2, "b.jpg"))
        assertNull(VmLogic.episodeImage(l[0], l, "poster.jpg"))
    }

    @Test fun blankOrMissingImageIsNull() {
        val l = listOf(ep(1, null), ep(2, " "))
        assertNull(VmLogic.episodeImage(l[0], l, null))
        assertNull(VmLogic.episodeImage(l[1], l, null))
    }
}

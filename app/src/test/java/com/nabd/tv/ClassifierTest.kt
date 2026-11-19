package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifierTest {
    @Test fun qualityAndNoiseDoNotChangeTheKey() {
        val keys = listOf("beIN Sports 1 HD", "BEIN SPORTS 1 FHD", "[AR] beIN Sports 1 | VIP").map(Classifier::normalize)
        assertEquals(1, keys.toSet().size)
        assertEquals("bein sports 1", keys.first())
    }

    @Test fun arabicLetterVariantsAreUnified() {
        assertEquals(Classifier.normalize("إخبارية"), Classifier.normalize("اخباريه"))
    }

    @Test fun differentChannelsKeepDifferentKeys() {
        assertTrue(Classifier.normalize("bein sports 1") != Classifier.normalize("bein sports 2"))
    }

    @Test fun cleanGroupRemovesNoiseButNeverReturnsEmpty() {
        assertEquals("News", Classifier.cleanGroup("|AR| News", listOf("|AR|")))
        assertEquals("|AR|", Classifier.cleanGroup("|AR|", listOf("|AR|")))
    }
}

package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class OriginNamesTest {
    @Test fun languageCodesBecomeNames() {
        assertEquals("English", Origin.languageName("en", "en"))
        assertEquals("Korean", Origin.languageName("ko", "en"))
        assertEquals("Cantonese", Origin.languageName("cn", "en"))
    }

    @Test fun countryCodesBecomeNames() {
        assertEquals("United States", Origin.countryName("US", "en"))
        assertEquals("Egypt", Origin.countryName("eg", "en"))
    }

    @Test fun providerTextIsKeptAsIs() {
        assertEquals("United States of America", Origin.countryName("United States of America", "en"))
        assertEquals("USA", Origin.countryName("USA", "en"))
        assertEquals("Arabic / English", Origin.languageName("Arabic / English", "en"))
    }
}

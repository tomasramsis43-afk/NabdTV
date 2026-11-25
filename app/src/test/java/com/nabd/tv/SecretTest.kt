package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretTest {
    @Test
    fun emptyPassesThrough() {
        assertEquals("", Secret.enc(""))
        assertEquals("", Secret.dec(""))
    }

    @Test
    fun isEncryptedPrefix() {
        assertFalse(Secret.isEncrypted("plain"))
        // On JVM (API level 0) enc returns plain; isEncrypted false
        assertFalse(Secret.isEncrypted(Secret.enc("hello")))
    }

    @Test
    fun decOfPlainReturnsPlain() {
        assertEquals("hello", Secret.dec("hello"))
    }
}
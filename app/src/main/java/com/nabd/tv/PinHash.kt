package com.nabd.tv

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Slow, salted PIN hashing (PBKDF2). Pure JVM so it can be unit-tested. */
object PinHash {
    const val ITERATIONS = 120_000

    fun hash(pin: String, saltHex: String, iterations: Int, algo: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), saltHex.toByteArray(), iterations, 256)
        val bytes = SecretKeyFactory.getInstance(algo).generateSecret(spec).encoded
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Constant-time comparison of two hex digests. */
    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

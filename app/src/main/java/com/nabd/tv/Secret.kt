package com.nabd.tv

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts stored login details with a key that never leaves the Android Keystore (API 23+). */
object Secret {
    private const val ALIAS = "nabd_secret_key"
    private const val PREFIX = "enc:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
        )
        return gen.generateKey()
    }

    fun isEncrypted(s: String) = s.startsWith(PREFIX)

    /** Thrown when a secret cannot be encrypted; callers must not store the plain value. */
    class SecretException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    fun enc(plain: String): String {
        if (plain.isEmpty()) return plain
        if (Build.VERSION.SDK_INT < 23) return plain   // legacy devices: no keystore available; value stays as-is
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            PREFIX + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        } catch (e: Exception) {
            Diag.note("Secret.enc (NOT stored)", e)
            throw SecretException("Could not encrypt the secret; it was not saved.", e)
        }
    }

    /** Returns null instead of throwing, for callers that want to handle failure gracefully. */
    fun encOrNull(plain: String): String? = try { enc(plain) } catch (e: SecretException) { null }

    /** Plain (legacy) values pass through unchanged; an undecryptable value yields "". */
    // Keystore decryption is slow (milliseconds, more on TV boxes) and the TMDB key is read by every poster card on every
    // recomposition while scrolling. Successful results are kept in memory, keyed by the encrypted value (so a changed key misses).
    // Bounded LRU (max 64) so secrets do not accumulate in memory; protected by [decLock].
    private val decLock = Any()
    private val decCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 64
    }

    fun dec(s: String): String {
        if (!isEncrypted(s)) return s
        synchronized(decLock) { decCache[s]?.let { return it } }
        return try {
            val raw = Base64.decode(s.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
            val out = String(c.doFinal(raw, 12, raw.size - 12))
            synchronized(decLock) { decCache[s] = out }
            out
        } catch (e: Exception) { Diag.note("Secret.dec (value unreadable)", e); "" }
    }

    /** Returns null when the value is encrypted but cannot be decrypted (e.g. lost Keystore key). */
    fun decOrNull(s: String): String? {
        if (!isEncrypted(s)) return s
        // probe by attempting; null on failure
        return try { dec(s).let { if (it.isEmpty() && isEncrypted(s)) null else it } } catch (e: Exception) { null }
    }
}

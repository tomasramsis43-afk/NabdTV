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

    fun enc(plain: String): String {
        if (plain.isEmpty() || Build.VERSION.SDK_INT < 23) return plain
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            PREFIX + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        } catch (e: Exception) { Diag.note("Secret.enc (stored unencrypted)", e); plain }
    }

    /** Plain (legacy) values pass through unchanged; an undecryptable value yields "". */
    // Keystore decryption is slow (milliseconds, more on TV boxes) and the TMDB key is read by every poster card on every
    // recomposition while scrolling. Successful results are kept in memory, keyed by the encrypted value (so a changed key misses).
    private val decCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun dec(s: String): String {
        if (!isEncrypted(s)) return s
        decCache[s]?.let { return it }
        return try {
            val raw = Base64.decode(s.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
            String(c.doFinal(raw, 12, raw.size - 12)).also { decCache[s] = it }
        } catch (e: Exception) { Diag.note("Secret.dec (value unreadable)", e); "" }
    }
}

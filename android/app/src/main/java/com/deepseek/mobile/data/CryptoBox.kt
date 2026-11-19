package com.deepseek.mobile.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore backed AES-GCM helper for the values that must not sit in
 * plain SharedPreferences (the DeepSeek API key and the bridge device token).
 *
 * A device with an unusable Keystore must not brick the app, so a failure
 * degrades to a clearly marked plaintext form instead of losing the value.
 */
object CryptoBox {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "dsh-mobile-secret"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val PLAIN_PREFIX = "plain:"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + cipherText.size)
            iv.copyInto(combined, 0)
            cipherText.copyInto(combined, iv.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        }.getOrElse {
            PLAIN_PREFIX + Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }

    fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        if (encoded.startsWith(PLAIN_PREFIX)) {
            return runCatching {
                String(Base64.decode(encoded.removePrefix(PLAIN_PREFIX), Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrDefault("")
        }
        return runCatching {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, IV_BYTES)
            val cipherText = combined.copyOfRange(IV_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrDefault("")
    }
}

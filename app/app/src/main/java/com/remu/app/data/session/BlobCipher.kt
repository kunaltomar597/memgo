package com.remu.app.data.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the session blob before DataStore writes it. Output is IV (12 bytes) + ciphertext. */
interface BlobCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/** AES-256-GCM over a [SecretKey]; the key source decides where the key lives. */
open class AesGcmCipher(private val key: () -> SecretKey) : BlobCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        require(iv.size == IV_BYTES) { "unexpected IV length" }
        return iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "blob too short" }
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_BYTES))
        return c.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/**
 * The production cipher: an AES-256-GCM key generated inside the Android Keystore, never
 * exportable. Replaces EncryptedSharedPreferences, which is deprecated (TASK-041).
 */
class KeystoreBlobCipher(private val alias: String = "remu_session_v1") : AesGcmCipher({ keystoreKey(alias) }) {
    companion object {
        private fun keystoreKey(alias: String): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return gen.generateKey()
        }
    }
}

/** A JVM key for unit tests, where the Android Keystore does not exist. */
class InMemoryKeyCipher : AesGcmCipher({ KEY }) {
    companion object {
        private val KEY: SecretKey by lazy {
            KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }.generateKey()
        }
    }
}

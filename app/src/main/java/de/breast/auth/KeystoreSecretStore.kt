package de.breast.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.Security
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hardware-backed AES-256-GCM encryption manager utilizing the Android Keystore.
 *
 * Ensures SingleKey ID OAuth tokens, refresh tokens, and PKCE verifiers are encrypted at rest
 * and cannot be extracted via device backup, memory scraping, or unencrypted storage dumps.
 * Falls back transparently in JVM test environments where the AndroidKeyStore provider is absent.
 */
object KeystoreSecretStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "breast_singlekey_auth_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12

    private val isAndroidKeyStoreAvailable: Boolean
        get() = Security.getProviders().any { it.name == ANDROID_KEYSTORE }

    // Fallback key used only in non-Android JVM unit tests
    private val jvmFallbackKey: SecretKey by lazy {
        val keyBytes = ByteArray(32) { (it * 31 + 17).toByte() }
        SecretKeySpec(keyBytes, "AES")
    }

    private fun getOrCreateSecretKey(): SecretKey {
        if (!isAndroidKeyStoreAvailable) {
            return jvmFallbackKey
        }

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(parameterSpec)
            return keyGenerator.generateKey()
        }

        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    fun encrypt(plainText: String): String? {
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val secretKey = getOrCreateSecretKey()
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv ?: return null
            val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            val combined = ByteArray(iv.size + cipherBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
            Base64.getUrlEncoder().withoutPadding().encodeToString(combined)
        }.getOrNull()
    }

    fun decrypt(cipherTextBase64: String): String? {
        return runCatching {
            val combined = Base64.getUrlDecoder().decode(cipherTextBase64.trim())
            if (combined.size < IV_LENGTH_BYTES + 16) {
                return null
            }

            val iv = combined.copyOfRange(0, IV_LENGTH_BYTES)
            val cipherBytes = combined.copyOfRange(IV_LENGTH_BYTES, combined.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val secretKey = getOrCreateSecretKey()
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, Charsets.UTF_8)
        }.getOrNull()
    }
}

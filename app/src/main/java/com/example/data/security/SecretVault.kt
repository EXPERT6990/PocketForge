package com.example.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local-only AES-256-GCM vault backed by Android Keystore for storing user-supplied
 * third-party API keys (OpenAI, Anthropic, OpenCode providers) and workspace secrets.
 */
class SecretVault(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSecret(alias: String, plainText: String) {
        val trimmed = plainText.trim()
        if (trimmed.isEmpty()) {
            deleteSecret(alias)
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
            val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
            prefs.edit().putString(alias, "$PREFIX_GCM$encoded").apply()
        } catch (e: Exception) {
            Log.e(TAG, "Keystore encryption failed for alias=$alias", e)
            throw IllegalStateException("Secure Keystore encryption unavailable: ${e.localizedMessage}", e)
        }
    }

    fun getSecret(alias: String): String? {
        val raw = prefs.getString(alias, null) ?: return null
        if (!raw.startsWith(PREFIX_GCM)) return null
        return try {
            val combined = Base64.decode(raw.removePrefix(PREFIX_GCM), Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return null
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipherText = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Keystore decryption failed for alias=$alias", e)
            null
        }
    }

    fun hasSecret(alias: String): Boolean = !prefs.getString(alias, null).isNullOrBlank()

    fun getMaskedPreview(alias: String): String? {
        val secret = getSecret(alias) ?: return null
        if (secret.length <= 8) return "••••••••"
        return "${secret.take(4)}••••${secret.takeLast(4)}"
    }

    fun deleteSecret(alias: String) {
        prefs.edit().remove(alias).apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val TAG = "SecretVault"
        private const val PREFS_NAME = "pocketforge_encrypted_vault"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "pocketforge_aes256_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val PREFIX_GCM = "gcm1:"
    }
}

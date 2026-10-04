package com.pengshi.words.sync

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class AndroidTokenStore(context: Context) : GitHubTokenStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override suspend fun readToken(): String? = readSecret(TOKEN_KEY)

    override suspend fun saveToken(token: String) {
        saveSecret(TOKEN_KEY, token)
    }

    override suspend fun clearToken() {
        preferences.edit().remove(TOKEN_KEY).apply()
    }

    fun hasToken(): Boolean = preferences.contains(TOKEN_KEY)

    fun hasSecret(key: String): Boolean = preferences.contains(key)

    suspend fun readSecret(key: String): String? = runCatching {
        val stored = preferences.getString(key, null) ?: return@runCatching null
        val parts = stored.split(':', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, decode(parts[0])))
        cipher.doFinal(decode(parts[1])).toString(StandardCharsets.UTF_8)
    }.getOrNull()

    suspend fun saveSecret(key: String, value: String) {
        require(value.isNotBlank())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        preferences.edit()
            .putString(key, "${encode(iv)}:${encode(encrypted)}")
            .apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val PREFERENCES = "pengshi-sync-secrets"
        const val TOKEN_KEY = "github-token"
        const val KEY_ALIAS = "pengshi-sync-key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}

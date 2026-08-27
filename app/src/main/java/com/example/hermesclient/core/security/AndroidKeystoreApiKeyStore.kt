package com.example.hermesclient.core.security

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class AndroidKeystoreApiKeyStore @Inject constructor(
    @ApplicationContext context: Context,
) : ApiKeyStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val encryptedValue = preferences.getString(KEY_CIPHERTEXT, null) ?: return@withLock null
            val initializationVector = preferences.getString(KEY_INITIALIZATION_VECTOR, null)
                ?: return@withLock clearCorruptValue()

            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateSecretKey(),
                    GCMParameterSpec(AUTHENTICATION_TAG_LENGTH_BITS, decode(initializationVector)),
                )
                val plaintext = cipher.doFinal(decode(encryptedValue))
                try {
                    plaintext.toString(Charsets.UTF_8)
                } finally {
                    plaintext.fill(0)
                }
            } catch (_: GeneralSecurityException) {
                clearCorruptValue()
            } catch (_: IllegalArgumentException) {
                clearCorruptValue()
            }
        }
    }

    @SuppressLint("UseKtx")
    override suspend fun save(apiKey: String) = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "API key must not be blank" }

        mutex.withLock {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val plaintext = apiKey.toByteArray(Charsets.UTF_8)
            val ciphertext = try {
                cipher.doFinal(plaintext)
            } finally {
                plaintext.fill(0)
            }

            check(
                preferences.edit()
                    .putString(KEY_CIPHERTEXT, encode(ciphertext))
                    .putString(KEY_INITIALIZATION_VECTOR, encode(cipher.iv))
                    .commit(),
            ) { "Unable to persist the encrypted API key" }
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun clearCorruptValue(): String? {
        preferences.edit {
            remove(KEY_CIPHERTEXT)
            remove(KEY_INITIALIZATION_VECTOR)
        }
        return null
    }

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "hermes_client_api_key"
        const val PREFERENCES_NAME = "hermes_secure_credentials"
        const val KEY_CIPHERTEXT = "api_key_ciphertext"
        const val KEY_INITIALIZATION_VECTOR = "api_key_iv"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val AUTHENTICATION_TAG_LENGTH_BITS = 128
    }
}

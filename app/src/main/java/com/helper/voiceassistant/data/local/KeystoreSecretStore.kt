package com.helper.voiceassistant.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.helper.voiceassistant.BuildConfig
import com.helper.voiceassistant.domain.repository.SecretKey
import com.helper.voiceassistant.domain.repository.SecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey as JcaSecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * API keys are encrypted with AES-256-GCM using a non-exportable key held in the Android Keystore;
 * only ciphertext (IV ‖ data, Base64) is written to SharedPreferences. Debug builds fall back to keys
 * from local.properties (BuildConfig.DEV_*), release builds contain none.
 */
@Singleton
class KeystoreSecretStore @Inject constructor(
    @ApplicationContext context: Context,
) : SecretStore {

    private val prefs = context.getSharedPreferences("secure_store", Context.MODE_PRIVATE)
    private val cache = mutableMapOf<SecretKey, String?>()

    init {
        // Speech recognition is offline now: drop a leftover cloud STT key from older versions.
        if (prefs.contains("STT_API_KEY")) prefs.edit { remove("STT_API_KEY") }
    }

    @Synchronized
    override fun get(key: SecretKey): String? {
        if (cache.containsKey(key)) return cache[key]
        val stored = prefs.getString(key.name, null)?.let { decrypt(it) }
        val value = stored?.takeIf { it.isNotBlank() } ?: devFallback(key)
        cache[key] = value
        return value
    }

    @Synchronized
    override fun put(key: SecretKey, value: String?) {
        cache.remove(key)
        prefs.edit {
            if (value.isNullOrBlank()) remove(key.name) else putString(key.name, encrypt(value.trim()))
        }
    }

    /** True when a key was entered in the app (not just provided by a debug build). */
    fun isUserProvided(key: SecretKey): Boolean = prefs.contains(key.name)

    private fun devFallback(key: SecretKey): String? = when (key) {
        SecretKey.DEEPSEEK_API_KEY -> BuildConfig.DEV_DEEPSEEK_API_KEY
    }.takeIf { it.isNotBlank() }

    private fun secretKey(): JcaSecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? = try {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
    } catch (e: Exception) {
        Timber.e("Stored secret could not be decrypted (%s); it must be entered again", e.javaClass.simpleName)
        null
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "voice_assistant_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}

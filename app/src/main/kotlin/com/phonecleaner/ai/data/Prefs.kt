package com.phonecleaner.ai.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * App settings. The API key is encrypted with an AES key that lives in the
 * Android Keystore and never leaves the device; only ciphertext is stored.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v).apply()

    var dnsHost: String
        get() = sp.getString("dns", DEFAULT_DNS) ?: DEFAULT_DNS
        set(v) = sp.edit().putString("dns", v).apply()

    fun hasApiKey() = sp.contains("key_ct")

    fun apiKey(): String? {
        val ct = sp.getString("key_ct", null) ?: return null
        val iv = sp.getString("key_iv", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun setApiKey(key: String?) {
        if (key.isNullOrBlank()) {
            sp.edit().remove("key_ct").remove("key_iv").apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(key.trim().toByteArray(Charsets.UTF_8))
        sp.edit()
            .putString("key_ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .putString("key_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val DEFAULT_DNS = "dns.adguard-dns.com"
        private const val ALIAS = "pca_api_key"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

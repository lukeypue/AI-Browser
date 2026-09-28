package com.appgate.tv.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.appgate.brain.planner.PlannerConfig
import com.appgate.brain.planner.PlannerProvider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The reasoning-model API key, encrypted with a hardware-backed Android Keystore key and stored
 * only on this phone. It is used for exactly one thing: calling the planner when the brain is
 * stuck. It is never logged, exported or included in any diagnostics bundle.
 */
object PlannerKeyStore {
    private const val KEY_ALIAS = "ai_browser_teacher_api_key"   // kept from earlier builds so an existing key keeps working
    private const val PREFS = "ai_teacher_secure"
    private const val CIPHERTEXT = "api_key_ciphertext"
    private const val IV = "api_key_iv"
    private const val SETTINGS = "planner_settings"

    fun save(context: Context, apiKey: String) {
        val clean = apiKey.trim()
        require(clean.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun load(context: Context): String? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cipherText = prefs.getString(CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(IV, null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun isConfigured(context: Context): Boolean = !load(context).isNullOrBlank()

    fun provider(context: Context): PlannerProvider = runCatching {
        PlannerProvider.valueOf(context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getString("provider", PlannerProvider.OPENAI.name)!!)
    }.getOrDefault(PlannerProvider.OPENAI)

    fun model(context: Context): String = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getString("model", "").orEmpty()

    fun saveSettings(context: Context, provider: PlannerProvider, model: String) {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putString("provider", provider.name).putString("model", model.trim()).apply()
    }

    /** Guess the provider from the key shape so most people never have to pick one. */
    fun guessProvider(apiKey: String): PlannerProvider = when {
        apiKey.startsWith("sk-ant-") -> PlannerProvider.ANTHROPIC
        else -> PlannerProvider.OPENAI
    }

    fun config(context: Context): PlannerConfig? {
        val key = load(context) ?: return null
        return PlannerConfig(provider = provider(context), apiKey = key, model = model(context))
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

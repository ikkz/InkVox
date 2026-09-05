package com.inkvox

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class CloudCredentials(
    val apiKey: String,
    val workspaceId: String,
)

internal object CloudConfig {
    private val workspacePattern = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

    fun isValidWorkspaceId(value: String): Boolean = workspacePattern.matches(value)
    fun isValidApiKey(value: String): Boolean = value.isNotBlank() && '\r' !in value && '\n' !in value

    @Synchronized
    fun save(context: Context, apiKey: String, workspaceId: String): Boolean {
        if (!isValidApiKey(apiKey) || !isValidWorkspaceId(workspaceId)) return false
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            }
            val plaintext = JSONObject()
                .put("apiKey", apiKey)
                .put("workspaceId", workspaceId)
                .toString()
                .toByteArray(Charsets.UTF_8)
            val encrypted = cipher.doFinal(plaintext)
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun load(context: Context): CloudCredentials? {
        return try {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val encrypted = Base64.decode(preferences.getString(CIPHERTEXT, null), Base64.NO_WRAP)
            val iv = Base64.decode(preferences.getString(IV, null), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            }
            val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
            CloudCredentials(
                apiKey = json.getString("apiKey"),
                workspaceId = json.getString("workspaceId"),
            ).takeIf {
                isValidApiKey(it.apiKey) && isValidWorkspaceId(it.workspaceId)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun hasConfig(context: Context): Boolean = load(context) != null

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "inkvox_cloud_config_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFERENCES = "cloud_config"
    private const val CIPHERTEXT = "ciphertext"
    private const val IV = "iv"
}

package com.rendyhd.vicu.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_prefs")

@Singleton
class SecureTokenStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val JWT = stringPreferencesKey("jwt_enc")
        val JWT_EXPIRY = longPreferencesKey("jwt_expiry")
        val API_TOKEN = stringPreferencesKey("api_token_enc")
        val API_TOKEN_EXPIRY = longPreferencesKey("api_token_expiry")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token_enc")
        val SERVER_IS_V2 = stringPreferencesKey("server_is_v2")
        val AUTH_METHOD = stringPreferencesKey("auth_method")
        val PROVIDER_KEY = stringPreferencesKey("provider_key")
        val VIKUNJA_URL = stringPreferencesKey("vikunja_url")
        val INBOX_PROJECT_ID = longPreferencesKey("inbox_project_id")
    }

    companion object {
        private const val TAG = "SecureTokenStorage"
        private const val MASTER_KEY_ALIAS = "vicu_master_key"
        private const val KEYSET_NAME = "vicu_keyset"
        private const val KEYSET_PREFS = "vicu_keyset_prefs"
    }

    private val aead: Aead by lazy {
        AeadConfig.register()
        try {
            buildAead()
        } catch (e: java.security.InvalidKeyException) {
            Log.w(TAG, "Master key invalid (signing key changed?), resetting keystore", e)
            resetKeystore()
            buildAead()
        } catch (e: java.security.GeneralSecurityException) {
            Log.w(TAG, "Keyset corrupted, resetting keystore", e)
            resetKeystore()
            buildAead()
        }
    }

    private fun buildAead(): Aead {
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://$MASTER_KEY_ALIAS")
            .build()
            .keysetHandle
        return keysetHandle.getPrimitive(Aead::class.java)
    }

    private fun resetKeystore() {
        // Remove the master key from Android Keystore
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry(MASTER_KEY_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete master key", e)
        }
        // Clear the Tink keyset SharedPreferences
        context.getSharedPreferences(KEYSET_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun encrypt(plaintext: String): String {
        val ciphertext = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), null)
        return Base64.getEncoder().encodeToString(ciphertext)
    }

    private fun decrypt(encoded: String): String {
        val ciphertext = Base64.getDecoder().decode(encoded)
        return String(aead.decrypt(ciphertext, null), Charsets.UTF_8)
    }

    // JWT
    suspend fun storeJwt(jwt: String, expiry: Long) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.JWT] = encrypt(jwt)
            prefs[Keys.JWT_EXPIRY] = expiry
        }
    }

    suspend fun getJwt(): String? {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.JWT]?.let { decrypt(it) }
    }

    suspend fun getJwtExpiry(): Long {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.JWT_EXPIRY] ?: 0L
    }

    // API Token
    suspend fun storeApiToken(token: String, expiry: Long) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.API_TOKEN] = encrypt(token)
            prefs[Keys.API_TOKEN_EXPIRY] = expiry
        }
    }

    suspend fun getApiToken(): String? {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.API_TOKEN]?.let { decrypt(it) }
    }

    suspend fun getApiTokenExpiry(): Long {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.API_TOKEN_EXPIRY] ?: 0L
    }

    // Refresh Token (Vikunja 2.0 session cookie)
    suspend fun storeRefreshToken(token: String) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.REFRESH_TOKEN] = encrypt(token)
        }
    }

    suspend fun getRefreshToken(): String? {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.REFRESH_TOKEN]?.let { decrypt(it) }
    }

    // Server version flag
    suspend fun storeServerIsV2(isV2: Boolean) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.SERVER_IS_V2] = isV2.toString()
        }
    }

    suspend fun getServerIsV2(): Boolean {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.SERVER_IS_V2] == "true"
    }

    // Auth method
    suspend fun storeAuthMethod(method: String) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.AUTH_METHOD] = method
        }
    }

    suspend fun getAuthMethod(): String? {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.AUTH_METHOD]
    }

    val authMethodFlow: Flow<String?> = context.authDataStore.data.map { it[Keys.AUTH_METHOD] }

    // Provider key (OIDC)
    suspend fun storeProviderKey(key: String) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.PROVIDER_KEY] = key
        }
    }

    suspend fun getProviderKey(): String? {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.PROVIDER_KEY]
    }

    // Vikunja URL
    suspend fun storeVikunjaUrl(url: String) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.VIKUNJA_URL] = url
        }
    }

    suspend fun getVikunjaUrl(): String? {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.VIKUNJA_URL]
    }

    val vikunjaUrlFlow: Flow<String?> = context.authDataStore.data.map { it[Keys.VIKUNJA_URL] }

    // Inbox project ID
    suspend fun storeInboxProjectId(id: Long) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.INBOX_PROJECT_ID] = id
        }
    }

    suspend fun getInboxProjectId(): Long? {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.INBOX_PROJECT_ID]
    }

    // Clear all
    suspend fun clear() {
        context.authDataStore.edit { it.clear() }
    }
}

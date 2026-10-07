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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import java.util.Base64

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_prefs")

class AndroidSecureTokenStorage(
    private val context: Context,
) : TokenStorage {
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
        val USER_ID = longPreferencesKey("user_id")
        val BACKUP_TOKEN_ID = longPreferencesKey("backup_api_token_id")
        val INSTALL_ID = stringPreferencesKey("install_id")
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
        // Clear the Tink keyset SharedPreferences so the next buildAead() regenerates the master key.
        context.getSharedPreferences(KEYSET_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        // Intentionally do NOT clear DataStore here: this path can fire from inside
        // authDataStore.edit { encrypt(...) } (e.g. storeJwt), and runBlocking { edit { } }
        // re-enters the same DataStore mutex and deadlocks. Stale ciphertext left in
        // DataStore is harmless — decryptOrNull() returns null for it and the normal
        // re-auth flow regenerates the values with the new key.
    }

    private fun encrypt(plaintext: String): String {
        val ciphertext = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), null)
        return Base64.getEncoder().encodeToString(ciphertext)
    }

    private fun decrypt(encoded: String): String {
        val ciphertext = Base64.getDecoder().decode(encoded)
        return String(aead.decrypt(ciphertext, null), Charsets.UTF_8)
    }

    /**
     * Attempt to decrypt; return null on failure (e.g. keystore was reset and
     * the ciphertext was produced with a previous key).
     */
    private fun decryptOrNull(encoded: String): String? {
        return try {
            decrypt(encoded)
        } catch (e: java.security.GeneralSecurityException) {
            Log.w(TAG, "Decryption failed (keystore reset?), returning null", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Base64 decode failed, returning null", e)
            null
        }
    }

    // JWT
    override suspend fun storeJwt(jwt: String, expiry: Long) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.JWT] = encrypt(jwt)
            prefs[Keys.JWT_EXPIRY] = expiry
        }
    }

    override suspend fun getJwt(): String? {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.JWT]?.let { decryptOrNull(it) }
    }

    override suspend fun getJwtExpiry(): Long {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.JWT_EXPIRY] ?: 0L
    }

    // API Token
    override suspend fun storeApiToken(token: String, expiry: Long) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.API_TOKEN] = encrypt(token)
            prefs[Keys.API_TOKEN_EXPIRY] = expiry
            // A user-supplied token is not ours to revoke.
            prefs.remove(Keys.BACKUP_TOKEN_ID)
        }
    }

    override suspend fun storeBackupApiToken(token: String, expiry: Long, tokenId: Long) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.API_TOKEN] = encrypt(token)
            prefs[Keys.API_TOKEN_EXPIRY] = expiry
            prefs[Keys.BACKUP_TOKEN_ID] = tokenId
        }
    }

    override suspend fun getBackupApiTokenId(): Long? {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.BACKUP_TOKEN_ID]
    }

    override suspend fun getApiToken(): String? {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.API_TOKEN]?.let { decryptOrNull(it) }
    }

    override suspend fun getApiTokenExpiry(): Long {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.API_TOKEN_EXPIRY] ?: 0L
    }

    /**
     * Whether a usable API token is stored. Ciphertext that can no longer be decrypted (the
     * keystore was reset, or the app was restored onto another device) does not count, so
     * AuthManager recreates the backup token instead of keeping a token it cannot read.
     */
    override suspend fun hasApiToken(): Boolean = getApiToken() != null

    override suspend fun getInstallId(): String {
        val existing = context.authDataStore.data.first()[Keys.INSTALL_ID]
        if (existing != null && ApiTokenTitle.isValidInstallId(existing)) return existing

        var installId = ""
        context.authDataStore.edit { prefs ->
            val current = prefs[Keys.INSTALL_ID]?.takeIf { ApiTokenTitle.isValidInstallId(it) }
            installId = current ?: ApiTokenTitle.newInstallId().also { prefs[Keys.INSTALL_ID] = it }
        }
        return installId
    }

    // Refresh Token (Vikunja 2.0 session cookie)
    override suspend fun storeRefreshToken(token: String) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.REFRESH_TOKEN] = encrypt(token)
        }
    }

    override suspend fun getRefreshToken(): String? {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.REFRESH_TOKEN]?.let { decryptOrNull(it) }
    }

    // Server version flag
    override suspend fun storeServerIsV2(isV2: Boolean) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.SERVER_IS_V2] = isV2.toString()
        }
    }

    override suspend fun getServerIsV2(): Boolean {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.SERVER_IS_V2] == "true"
    }

    // Auth method
    override suspend fun storeAuthMethod(method: String) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.AUTH_METHOD] = method
        }
    }

    override suspend fun getAuthMethod(): String? {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.AUTH_METHOD]
    }

    override val authMethodFlow: Flow<String?> = context.authDataStore.data.map { it[Keys.AUTH_METHOD] }

    // Provider key (OIDC)
    override suspend fun storeProviderKey(key: String) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.PROVIDER_KEY] = key
        }
    }

    override suspend fun getProviderKey(): String? {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.PROVIDER_KEY]
    }

    // Vikunja URL
    override suspend fun storeVikunjaUrl(url: String) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.VIKUNJA_URL] = url
        }
    }

    override suspend fun getVikunjaUrl(): String? {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.VIKUNJA_URL]
    }

    override val vikunjaUrlFlow: Flow<String?> = context.authDataStore.data.map { it[Keys.VIKUNJA_URL] }

    // Inbox project ID
    override suspend fun storeInboxProjectId(id: Long) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.INBOX_PROJECT_ID] = id
        }
    }

    override suspend fun getInboxProjectId(): Long? {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.INBOX_PROJECT_ID]
    }

    override val inboxProjectIdFlow: Flow<Long?> =
        context.authDataStore.data.map { it[Keys.INBOX_PROJECT_ID] }.distinctUntilChanged()

    override suspend fun clearInboxProjectId() {
        context.authDataStore.edit { prefs -> prefs.remove(Keys.INBOX_PROJECT_ID) }
    }

    // User id (identity of the signed-in account)
    override suspend fun storeUserId(id: Long) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.USER_ID] = id
        }
    }

    override suspend fun getUserId(): Long? {
        val prefs = context.authDataStore.data.first()
        return prefs[Keys.USER_ID]
    }

    // Clear all credentials and settings. The install id identifies this install rather than a
    // session, so it is kept: a token left behind on the server by an offline logout can then
    // still be recognised and cleaned up after the next login.
    override suspend fun clear() {
        context.authDataStore.edit { prefs ->
            val installId = prefs[Keys.INSTALL_ID]
            prefs.clear()
            if (installId != null) prefs[Keys.INSTALL_ID] = installId
        }
    }
}

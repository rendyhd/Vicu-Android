package com.rendyhd.vicu.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import platform.Foundation.*
import platform.Security.*
import platform.darwin.OSStatus
import kotlinx.cinterop.*

class IosKeychainTokenStorage : TokenStorage {
    private val defaults = NSUserDefaults.standardUserDefaults
    
    private val _authMethodFlow = MutableStateFlow<String?>(defaults.stringForKey(KEY_AUTH_METHOD))
    override val authMethodFlow: Flow<String?> = _authMethodFlow.asStateFlow()
    
    private val _vikunjaUrlFlow = MutableStateFlow<String?>(defaults.stringForKey(KEY_VIKUNJA_URL))
    private val _inboxProjectIdFlow = MutableStateFlow<Long?>(readInboxProjectId())
    override val inboxProjectIdFlow: Flow<Long?> = _inboxProjectIdFlow.asStateFlow()
    override val vikunjaUrlFlow: Flow<String?> = _vikunjaUrlFlow.asStateFlow()

    companion object {
        private const val KEY_AUTH_METHOD = "auth_method"
        private const val KEY_PROVIDER_KEY = "provider_key"
        private const val KEY_VIKUNJA_URL = "vikunja_url"
        private const val KEY_INBOX_PROJECT_ID = "inbox_project_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SERVER_IS_V2 = "server_is_v2"
        private const val KEY_JWT_EXPIRY = "jwt_expiry"
        private const val KEY_API_TOKEN_EXPIRY = "api_token_expiry"
        private const val KEY_BACKUP_TOKEN_ID = "backup_api_token_id"
        private const val KEY_INSTALL_ID = "install_id"
        
        private const val SERVICE_NAME = "com.rendyhd.vicu"
        private const val ACCOUNT_JWT = "jwt"
        private const val ACCOUNT_REFRESH_TOKEN = "refresh_token"
        private const val ACCOUNT_API_TOKEN = "api_token"
    }

    private fun saveKeychainString(account: String, value: String): Boolean {
        val query = NSMutableDictionary().apply {
            setObject(kSecClassGenericPassword, kSecClass)
            setObject(SERVICE_NAME, kSecAttrService)
            setObject(account, kSecAttrAccount)
        }
        
        // Delete any existing item first
        SecItemDelete(query as CFDictionaryRef)
        
        // Add new item
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return false
        query.setObject(data, kSecValueData)
        val status = SecItemAdd(query as CFDictionaryRef, null)
        return status == errSecSuccess
    }

    private fun getKeychainString(account: String): String? {
        val query = NSMutableDictionary().apply {
            setObject(kSecClassGenericPassword, kSecClass)
            setObject(SERVICE_NAME, kSecAttrService)
            setObject(account, kSecAttrAccount)
            setObject(kCFBooleanTrue, kSecReturnData)
            setObject(kSecMatchLimitOne, kSecMatchLimit)
        }
        
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query as CFDictionaryRef, result.ptr)
            if (status == errSecSuccess) {
                val data = result.value as? NSData ?: return null
                return NSString.create(data = data, encoding = NSUTF8StringEncoding) as String?
            }
        }
        return null
    }

    private fun deleteKeychainItem(account: String) {
        val query = NSMutableDictionary().apply {
            setObject(kSecClassGenericPassword, kSecClass)
            setObject(SERVICE_NAME, kSecAttrService)
            setObject(account, kSecAttrAccount)
        }
        SecItemDelete(query as CFDictionaryRef)
    }

    override suspend fun storeJwt(jwt: String, expiry: Long) {
        saveKeychainString(ACCOUNT_JWT, jwt)
        defaults.setInteger(expiry, KEY_JWT_EXPIRY)
    }

    override suspend fun getJwt(): String? = getKeychainString(ACCOUNT_JWT)

    override suspend fun getJwtExpiry(): Long = defaults.integerForKey(KEY_JWT_EXPIRY)

    override suspend fun storeApiToken(token: String, expiry: Long) {
        saveKeychainString(ACCOUNT_API_TOKEN, token)
        defaults.setInteger(expiry, KEY_API_TOKEN_EXPIRY)
        // A user-supplied token is not ours to revoke.
        defaults.removeObjectForKey(KEY_BACKUP_TOKEN_ID)
    }

    override suspend fun storeBackupApiToken(token: String, expiry: Long, tokenId: Long) {
        saveKeychainString(ACCOUNT_API_TOKEN, token)
        defaults.setInteger(expiry, KEY_API_TOKEN_EXPIRY)
        defaults.setInteger(tokenId, KEY_BACKUP_TOKEN_ID)
    }

    override suspend fun getBackupApiTokenId(): Long? {
        val id = defaults.integerForKey(KEY_BACKUP_TOKEN_ID)
        return if (id == 0L) null else id
    }

    override suspend fun getApiToken(): String? = getKeychainString(ACCOUNT_API_TOKEN)

    override suspend fun getApiTokenExpiry(): Long = defaults.integerForKey(KEY_API_TOKEN_EXPIRY)

    override suspend fun hasApiToken(): Boolean = getApiToken() != null

    override suspend fun getInstallId(): String {
        val existing = defaults.stringForKey(KEY_INSTALL_ID)
        if (existing != null && ApiTokenTitle.isValidInstallId(existing)) return existing
        val created = ApiTokenTitle.newInstallId()
        defaults.setObject(created, KEY_INSTALL_ID)
        return created
    }

    override suspend fun storeRefreshToken(token: String) {
        saveKeychainString(ACCOUNT_REFRESH_TOKEN, token)
    }

    override suspend fun getRefreshToken(): String? = getKeychainString(ACCOUNT_REFRESH_TOKEN)

    override suspend fun storeServerIsV2(isV2: Boolean) {
        defaults.setBool(isV2, KEY_SERVER_IS_V2)
    }

    override suspend fun getServerIsV2(): Boolean = defaults.boolForKey(KEY_SERVER_IS_V2)

    override suspend fun storeAuthMethod(method: String) {
        defaults.setObject(method, KEY_AUTH_METHOD)
        _authMethodFlow.value = method
    }

    override suspend fun getAuthMethod(): String? = defaults.stringForKey(KEY_AUTH_METHOD)

    override suspend fun storeProviderKey(key: String) {
        defaults.setObject(key, KEY_PROVIDER_KEY)
    }

    override suspend fun getProviderKey(): String? = defaults.stringForKey(KEY_PROVIDER_KEY)

    override suspend fun storeVikunjaUrl(url: String) {
        defaults.setObject(url, KEY_VIKUNJA_URL)
        _vikunjaUrlFlow.value = url
    }

    override suspend fun getVikunjaUrl(): String? = defaults.stringForKey(KEY_VIKUNJA_URL)

    override suspend fun storeInboxProjectId(id: Long) {
        defaults.setInteger(id, KEY_INBOX_PROJECT_ID)
        _inboxProjectIdFlow.value = id
    }

    override suspend fun getInboxProjectId(): Long? = readInboxProjectId()

    private fun readInboxProjectId(): Long? {
        val id = defaults.integerForKey(KEY_INBOX_PROJECT_ID)
        return if (id == 0L) null else id
    }

    override suspend fun clearInboxProjectId() {
        defaults.removeObjectForKey(KEY_INBOX_PROJECT_ID)
        _inboxProjectIdFlow.value = null
    }

    override suspend fun storeUserId(id: Long) {
        defaults.setInteger(id, KEY_USER_ID)
    }

    override suspend fun getUserId(): Long? {
        val id = defaults.integerForKey(KEY_USER_ID)
        return if (id == 0L) null else id
    }

    override suspend fun clear() {
        deleteKeychainItem(ACCOUNT_JWT)
        deleteKeychainItem(ACCOUNT_API_TOKEN)
        deleteKeychainItem(ACCOUNT_REFRESH_TOKEN)
        defaults.removeObjectForKey(KEY_JWT_EXPIRY)
        defaults.removeObjectForKey(KEY_API_TOKEN_EXPIRY)
        defaults.removeObjectForKey(KEY_BACKUP_TOKEN_ID)
        defaults.removeObjectForKey(KEY_AUTH_METHOD)
        defaults.removeObjectForKey(KEY_PROVIDER_KEY)
        defaults.removeObjectForKey(KEY_VIKUNJA_URL)
        defaults.removeObjectForKey(KEY_INBOX_PROJECT_ID)
        defaults.removeObjectForKey(KEY_USER_ID)
        defaults.removeObjectForKey(KEY_SERVER_IS_V2)
        _authMethodFlow.value = null
        _vikunjaUrlFlow.value = null
        _inboxProjectIdFlow.value = null
    }
}

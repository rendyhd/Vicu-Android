package com.rendyhd.vicu.auth

import kotlinx.coroutines.flow.Flow

interface TokenStorage {
    suspend fun storeJwt(jwt: String, expiry: Long)
    suspend fun getJwt(): String?
    suspend fun getJwtExpiry(): Long

    /** Stores a token the user supplied. It is not ours to revoke, so any backup token id is cleared. */
    suspend fun storeApiToken(token: String, expiry: Long)

    /** Stores the backup token this app created, together with its server-side id, in one write. */
    suspend fun storeBackupApiToken(token: String, expiry: Long, tokenId: Long)
    suspend fun getApiToken(): String?
    suspend fun getApiTokenExpiry(): Long

    /** Server-side id of the backup token created by this app, or null for a user-supplied or unknown token. */
    suspend fun getBackupApiTokenId(): Long?

    /** True only when a stored API token exists and can actually be read back (decrypted). */
    suspend fun hasApiToken(): Boolean

    /**
     * Random id identifying this install, generated on first use. It survives [clear] (so a
     * re-login keeps its token titles) but is not backed up, so a restored device gets a new one.
     */
    suspend fun getInstallId(): String

    suspend fun storeRefreshToken(token: String)
    suspend fun getRefreshToken(): String?

    suspend fun storeServerIsV2(isV2: Boolean)
    suspend fun getServerIsV2(): Boolean

    suspend fun storeAuthMethod(method: String)
    suspend fun getAuthMethod(): String?
    val authMethodFlow: Flow<String?>

    suspend fun storeProviderKey(key: String)
    suspend fun getProviderKey(): String?

    suspend fun storeVikunjaUrl(url: String)
    suspend fun getVikunjaUrl(): String?
    val vikunjaUrlFlow: Flow<String?>

    suspend fun storeInboxProjectId(id: Long)
    suspend fun getInboxProjectId(): Long?

    /** The Inbox project id as it changes: Settings picks another one, or an account switch clears it. */
    val inboxProjectIdFlow: Flow<Long?>

    /** Forgets the Inbox project id, which belongs to the account that is being replaced. */
    suspend fun clearInboxProjectId()

    /**
     * Server-side id of the signed-in user, recorded at login (or backfilled for sessions that
     * predate it). Together with the server URL it tells a re-login of the same account from a
     * switch to another one. Null when unknown. Cleared by [clear].
     */
    suspend fun storeUserId(id: Long)
    suspend fun getUserId(): Long?

    suspend fun clear()
}

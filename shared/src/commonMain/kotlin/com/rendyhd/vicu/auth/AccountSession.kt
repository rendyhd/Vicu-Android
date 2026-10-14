package com.rendyhd.vicu.auth

import com.rendyhd.vicu.data.local.LocalDataWiper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService

/** What a verified login will do to local data, decided before anything is touched. */
data class LoginPlan(
    val identity: AccountIdentity,
    val action: LoginDataAction,
    /** Queued changes that [action] = WIPE_ALL would throw away (0 for KEEP). */
    val unsyncedActionsLost: Int,
)

/**
 * Login-time handling of local data for Setup (password, OIDC, API token).
 *
 * The order is what protects unsynced work:
 *  1. [verify] asks the server who the new credentials belong to, without storing anything, so a
 *     wrong token or a failed login changes nothing;
 *  2. [plan] compares that with the stored identity and counts what a wipe would lose;
 *  3. [apply] wipes local data only when the account really changed;
 *  4. the caller stores the new credentials, then [recordIdentity] stores who they belong to.
 */
class AccountSession(
    private val tokenStorage: TokenStorage,
    private val apiService: VikunjaApiService,
    private val wiper: LocalDataWiper,
) {
    /** The identity [token] belongs to on [serverUrl]. Stores nothing; throws if the server rejects it. */
    suspend fun verify(serverUrl: String, token: String): AccountIdentity {
        val user = apiService.getCurrentUserWithToken(serverUrl, token)
        if (user.id <= 0L) throw IllegalStateException("The server did not identify this account")
        return AccountIdentity(serverUrl, user.id)
    }

    suspend fun plan(incoming: AccountIdentity): LoginPlan {
        val storedUrl = tokenStorage.getVikunjaUrl()
        val previous = storedUrl?.takeIf { it.isNotBlank() }?.let { AccountIdentity(it, tokenStorage.getUserId()) }
        val action = AccountSwitch.decide(previous, incoming)
        val lost = if (action == LoginDataAction.WIPE_ALL) wiper.unsyncedChangeCount() else 0
        return LoginPlan(incoming, action, lost)
    }

    suspend fun apply(plan: LoginPlan) {
        if (plan.action == LoginDataAction.WIPE_ALL) {
            wiper.wipeEverything()
            // The Inbox project belonged to the account that is gone. Left in place, it would send
            // the app straight to an Inbox that does not exist for the new account, skipping the
            // project selection that sets the right one.
            tokenStorage.clearInboxProjectId()
        }
    }

    /** Call after the new credentials are stored. */
    suspend fun recordIdentity(identity: AccountIdentity) {
        identity.userId?.let { tokenStorage.storeUserId(it) }
    }
}

package com.rendyhd.vicu.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rendyhd.vicu.auth.AuthDebugLog
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.AuthState
import com.rendyhd.vicu.auth.RefreshFailure
import com.rendyhd.vicu.auth.RefreshResult
import com.rendyhd.vicu.auth.SecureTokenStorage
import com.rendyhd.vicu.data.remote.interceptor.BaseUrlHolder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Periodic WorkManager job that refreshes the JWT token.
 * Keeps the refresh token alive even when the app process is dead,
 * preventing "refresh token expired between app opens" logouts.
 */
@HiltWorker
class TokenRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val authManager: AuthManager,
    private val tokenStorage: SecureTokenStorage,
    private val baseUrlHolder: BaseUrlHolder,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "TokenRefreshWorker"
    }

    override suspend fun doWork(): Result {
        AuthDebugLog.log("WORKER_REFRESH", "periodic TokenRefreshWorker started (attempt=${runAttemptCount})")

        // Ensure base URL is set (cold start after process death)
        baseUrlHolder.ensureInitialized()
        if (baseUrlHolder.baseUrl.isEmpty()) {
            Log.d(TAG, "No Vikunja URL configured, skipping refresh")
            AuthDebugLog.log("WORKER_REFRESH", "skipped: no Vikunja URL")
            return Result.success()
        }

        // Only refresh if we have a refresh token
        val hasRefreshToken = tokenStorage.getRefreshToken() != null
        if (!hasRefreshToken) {
            Log.d(TAG, "No refresh token available, skipping")
            AuthDebugLog.log("WORKER_REFRESH", "skipped: no refresh token")
            return Result.success()
        }

        // Ensure AuthManager is initialized (loads tokens from DataStore on cold start)
        authManager.ensureInitializedAndGetToken()

        // Don't retry if user genuinely needs to re-authenticate
        if (authManager.authState.value != AuthState.Authenticated) {
            Log.d(TAG, "Not authenticated (${authManager.authState.value}), skipping refresh")
            AuthDebugLog.log("WORKER_REFRESH", "skipped: state=${authManager.authState.value}")
            return Result.success()
        }

        // Respect AuthManager's backoff window so WorkManager doesn't pile on retries
        // when the server is rate-limiting or 5xx-flapping.
        if (!authManager.canAttemptRefreshNow()) {
            AuthDebugLog.log("WORKER_REFRESH", "skipped: backoff window active")
            return Result.success()
        }

        return try {
            AuthDebugLog.refreshAttempt("WorkManager periodic")
            val result = authManager.withRefreshLock {
                authManager.performV2RefreshTyped()
            }
            when (result) {
                is RefreshResult.Success -> {
                    Log.d(TAG, "Periodic token refresh succeeded")
                    AuthDebugLog.refreshResult("WorkManager periodic", true)
                    Result.success()
                }
                is RefreshResult.Failure -> when (result.kind) {
                    // Terminal failures — don't churn WorkManager retries.
                    RefreshFailure.Unauthorized, RefreshFailure.NoRefreshToken -> {
                        AuthDebugLog.refreshResult("WorkManager periodic", false, "terminal=${result.kind::class.simpleName}")
                        Result.success()
                    }
                    else -> {
                        AuthDebugLog.refreshResult("WorkManager periodic", false, "transient=${result.kind::class.simpleName}, will retry")
                        Result.retry()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Periodic token refresh exception", e)
            AuthDebugLog.logError("WorkManager periodic refresh", e)
            Result.retry()
        }
    }
}

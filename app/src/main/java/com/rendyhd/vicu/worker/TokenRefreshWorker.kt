package com.rendyhd.vicu.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.AuthState
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
        // Ensure base URL is set (cold start after process death)
        baseUrlHolder.ensureInitialized()
        if (baseUrlHolder.baseUrl.isEmpty()) {
            Log.d(TAG, "No Vikunja URL configured, skipping refresh")
            return Result.success()
        }

        // Only refresh if we have a refresh token
        val hasRefreshToken = tokenStorage.getRefreshToken() != null
        if (!hasRefreshToken) {
            Log.d(TAG, "No refresh token available, skipping")
            return Result.success()
        }

        // Ensure AuthManager is initialized (loads tokens from DataStore on cold start)
        authManager.ensureInitializedAndGetToken()

        // Don't retry if user genuinely needs to re-authenticate
        if (authManager.authState.value != AuthState.Authenticated) {
            Log.d(TAG, "Not authenticated (${authManager.authState.value}), skipping refresh")
            return Result.success()
        }

        return try {
            // Note: performV2Refresh() internally calls onJwtRenewed() → scheduleProactiveRefresh(),
            // but that launch is async in appScope and does NOT re-enter the mutex.
            val success = authManager.withRefreshLock {
                authManager.performV2Refresh()
            }
            if (success) {
                Log.d(TAG, "Periodic token refresh succeeded")
                Result.success()
            } else {
                Log.w(TAG, "Periodic token refresh failed, will retry")
                Result.retry()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Periodic token refresh exception", e)
            Result.retry()
        }
    }
}

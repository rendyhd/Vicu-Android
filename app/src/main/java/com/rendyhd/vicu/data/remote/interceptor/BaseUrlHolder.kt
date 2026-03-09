package com.rendyhd.vicu.data.remote.interceptor

import android.util.Log
import com.rendyhd.vicu.auth.SecureTokenStorage
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BaseUrlHolder @Inject constructor(
    private val tokenStorage: SecureTokenStorage,
) {
    companion object {
        private const val TAG = "BaseUrlHolder"
    }

    @Volatile
    var baseUrl: String = ""

    fun getFullBaseUrl(): String {
        if (baseUrl.isEmpty()) return ""
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return "${normalized}api/v1/"
    }

    /**
     * Load baseUrl from DataStore if not yet set.
     * For use in coroutine contexts (workers, callbacks).
     */
    suspend fun ensureInitialized() {
        if (baseUrl.isEmpty()) {
            val url = tokenStorage.getVikunjaUrl()
            if (!url.isNullOrBlank()) {
                baseUrl = url
                Log.d(TAG, "Lazy-initialized baseUrl from DataStore")
            }
        }
    }

    /**
     * Blocking variant for OkHttp interceptor threads (non-coroutine context).
     */
    fun ensureInitializedBlocking() {
        if (baseUrl.isEmpty()) {
            runBlocking {
                ensureInitialized()
            }
        }
    }
}

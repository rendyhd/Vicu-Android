package com.rendyhd.vicu.util

sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    /**
     * [code] is the HTTP status when there was a response; [offline] means there was none (no
     * connection, DNS or TLS failure, timeout), so showing it as an error on every screen would only nag.
     */
    data class Error(val message: String, val code: Int? = null, val offline: Boolean = false) : NetworkResult<Nothing>()
    data object Loading : NetworkResult<Nothing>()
}

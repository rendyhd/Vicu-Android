package com.rendyhd.vicu.util

import io.ktor.client.plugins.ResponseException
import com.rendyhd.vicu.data.remote.api.VikunjaApiException

fun isRetriableNetworkError(e: Exception): Boolean {
    if (e is VikunjaApiException) {
        return e.httpStatus in 500..599 || e.httpStatus == 429
    }
    if (e is ResponseException) {
        val code = e.response.status.value
        return code in 500..599 || code == 429
    }
    return isPlatformRetriableError(e)
}

expect fun isPlatformRetriableError(e: Exception): Boolean

/**
 * True when [e] means no HTTP response was received at all: offline, DNS failure, timeout,
 * connection reset, TLS failure. Unlike [isRetriableNetworkError] this says nothing about
 * whether a retry is worthwhile, and TLS failures count.
 */
fun isNetworkFailure(e: Exception): Boolean = isPlatformNetworkFailure(e)

expect fun isPlatformNetworkFailure(e: Exception): Boolean

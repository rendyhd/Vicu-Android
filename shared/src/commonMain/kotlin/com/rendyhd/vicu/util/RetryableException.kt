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

/**
 * True when a request that failed may still have been carried out by the server: a 5xx, a
 * timeout, a connection that broke after the request went out. False when it certainly was not:
 * an answer that refuses it (4xx, 429) or a connection that never opened (no network, unknown
 * host, connection refused). A create that may have been carried out is looked for on the server
 * before it is sent again.
 */
fun mayHaveReachedServer(e: Exception): Boolean {
    if (e is VikunjaApiException) return e.httpStatus in 500..599
    if (e is ResponseException) return e.response.status.value in 500..599
    return !isPlatformConnectionNeverOpened(e)
}

/** True when [e] says the connection to the server was never made, so no request went out. */
expect fun isPlatformConnectionNeverOpened(e: Exception): Boolean

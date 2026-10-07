package com.rendyhd.vicu.util

import java.io.IOException
import javax.net.ssl.SSLException

actual fun isPlatformRetriableError(e: Exception): Boolean {
    // HTTP status errors (VikunjaApiException, Ktor's ResponseException) are decided in common code.
    if (e is SSLException || e.cause is SSLException) return false
    if (e is IOException) return true
    if (e.cause is IOException) return true
    return false
}

private const val MAX_CAUSE_DEPTH = 8

actual fun isPlatformNetworkFailure(e: Exception): Boolean {
    var current: Throwable? = e
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is IOException) return true
        current = current.cause
        depth++
    }
    return false
}

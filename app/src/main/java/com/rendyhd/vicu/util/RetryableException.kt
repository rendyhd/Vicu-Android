package com.rendyhd.vicu.util

import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

fun isRetriableNetworkError(e: Exception): Boolean {
    if (e is SSLHandshakeException) return false

    if (e is UnknownHostException || e is ConnectException || e is SocketTimeoutException) return true

    if (e is HttpException) {
        val code = e.code()
        return code in 500..599 || code == 429
    }

    // Check wrapped cause
    val cause = e.cause
    if (cause is UnknownHostException || cause is ConnectException || cause is SocketTimeoutException) return true
    if (cause is SSLHandshakeException) return false

    return false
}

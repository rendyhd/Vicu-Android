package com.rendyhd.vicu.util

import retrofit2.HttpException
import java.io.IOException
import javax.net.ssl.SSLException

/**
 * Transient failures that should be queued/retried offline. Any IOException counts as
 * transient connectivity EXCEPT SSL problems (configuration errors, retrying won't help) —
 * note SSLException IS an IOException, so the SSL check must come first.
 */
fun isRetriableNetworkError(e: Exception): Boolean {
    if (e is HttpException) {
        val code = e.code()
        return code in 500..599 || code == 429
    }
    if (e is SSLException || e.cause is SSLException) return false
    if (e is IOException) return true
    if (e.cause is IOException) return true
    return false
}

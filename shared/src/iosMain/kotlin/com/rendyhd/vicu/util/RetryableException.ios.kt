package com.rendyhd.vicu.util

actual fun isPlatformRetriableError(e: Exception): Boolean {
    val className = e::class.simpleName ?: ""
    val causeClassName = e.cause?.let { it::class.simpleName } ?: ""
    if (className.contains("SSL") || causeClassName.contains("SSL")) return false
    if (className.contains("IOException") || causeClassName.contains("IOException")) return true
    if (className.contains("Timeout") || causeClassName.contains("Timeout")) return true
    if (className.contains("Connect") || causeClassName.contains("Connect")) return true
    return false
}

private const val MAX_CAUSE_DEPTH = 8

actual fun isPlatformNetworkFailure(e: Exception): Boolean {
    var current: Throwable? = e
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        val name = current::class.simpleName ?: ""
        if (name.contains("IOException") || name.contains("Timeout") || name.contains("Connect") ||
            name.contains("SSL") || name.contains("Darwin")
        ) {
            return true
        }
        current = current.cause
        depth++
    }
    return false
}

/** Not told apart on iOS: every failure counts as one that may have reached the server. */
actual fun isPlatformConnectionNeverOpened(e: Exception): Boolean = false

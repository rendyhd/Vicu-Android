package com.rendyhd.vicu.util

/**
 * Release builds keep warnings and errors, but a request address in them (a server path, a query
 * with a task title, a sign-in name) is nobody's business. Every http(s) address is cut down to its
 * host: no user name, port, path or query.
 */
object LogScrub {
    private val address = Regex("""https?://[^\s/?#]*[^\s]*""", RegexOption.IGNORE_CASE)

    fun addresses(text: String): String = address.replace(text) { match ->
        val value = match.value
        val schemeEnd = value.indexOf("://") + 3
        val authority = value.substring(schemeEnd).takeWhile { it != '/' && it != '?' && it != '#' }
        val host = authority.substringAfterLast('@').let { hostPort ->
            // A bracketed IPv6 host keeps its brackets; otherwise the port goes.
            if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        }
        value.substring(0, schemeEnd) + host
    }

    /** The line a release build writes for an error: the type and the cleaned message, no stack trace. */
    fun throwable(tr: Throwable): String =
        " - ${tr::class.simpleName ?: "Error"}: ${tr.message?.let(::addresses).orEmpty()}"
}

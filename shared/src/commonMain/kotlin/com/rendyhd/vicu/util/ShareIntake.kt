package com.rendyhd.vicu.util

/**
 * Which file addresses another app may hand to Vicu through the share sheet.
 *
 * Only `content://` addresses count, because the sending app grants access to them. A `file://`
 * address would let another app name one of Vicu's own files (its database, its stored token) and
 * have it attached to a task. An address that belongs to Vicu's own file provider is refused for
 * the same reason: that provider can serve files from Vicu's private folders.
 */
object ShareIntake {
    /** True when [uri] may be attached; [ownAuthority] is this app's file provider authority. */
    fun isAcceptable(uri: String, ownAuthority: String): Boolean {
        val prefix = "content://"
        if (uri.length <= prefix.length || !uri.startsWith(prefix, ignoreCase = true)) return false
        val rest = uri.substring(prefix.length)
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.isEmpty() || authority.contains('@')) return false
        return !authority.equals(ownAuthority, ignoreCase = true)
    }

    fun acceptable(uris: List<String>, ownAuthority: String): List<String> =
        uris.filter { isAcceptable(it, ownAuthority) }
}

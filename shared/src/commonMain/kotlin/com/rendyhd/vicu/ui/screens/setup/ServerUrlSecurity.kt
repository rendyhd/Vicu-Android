package com.rendyhd.vicu.ui.screens.setup

/**
 * Whether [rawUrl] is a plain `http://` address to a host outside the user's own machine or
 * network. Cleartext stays allowed, because self-hosters run on a LAN or behind a VPN, but a
 * password or token that crosses the public internet unencrypted is worth a warning.
 *
 * An address without a scheme does not warn: Setup adds `https://` to it.
 */
internal fun isCleartextToRemoteHost(rawUrl: String): Boolean {
    val host = cleartextHostOf(rawUrl) ?: return false
    return !isLocalHost(host)
}

/** The lower-case host of an `http://` URL, or null for any other scheme or an empty host. */
internal fun cleartextHostOf(rawUrl: String): String? {
    val url = rawUrl.trim()
    if (!url.startsWith("http://", ignoreCase = true)) return null
    var authority = url.substring("http://".length).substringBefore('/').substringBefore('?').substringBefore('#')
    authority = authority.substringAfterLast('@')
    val host = if (authority.startsWith("[")) {
        authority.removePrefix("[").substringBefore(']')
    } else {
        authority.substringBefore(':')
    }
    return host.lowercase().takeIf { it.isNotEmpty() }
}

private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".lan", ".home", ".internal", ".home.arpa", ".test")

private fun isLocalHost(host: String): Boolean {
    if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) return true
    if (host.contains(':')) return isLocalIpv6(host)
    val octets = host.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
    if (octets.size == 4 && host.split('.').size == 4) return isPrivateIpv4(octets)
    // A single label ("nas", "vikunja") only resolves on the local network.
    return !host.contains('.')
}

private fun isPrivateIpv4(o: List<Int>): Boolean = when (o[0]) {
    10, 127 -> true
    172 -> o[1] in 16..31
    192 -> o[1] == 168
    169 -> o[1] == 254
    100 -> o[1] in 64..127
    else -> false
}

private fun isLocalIpv6(host: String): Boolean {
    if (host == "::1") return true
    // A first group shorter than four digits is below 0x1000, so never in these ranges.
    val first = host.substringBefore(':').lowercase()
    if (first.length != 4) return false
    // fc00::/7 (unique local) and fe80::/10 (link local).
    return first.startsWith("fc") || first.startsWith("fd") || (first.startsWith("fe") && first[2] in "89ab")
}

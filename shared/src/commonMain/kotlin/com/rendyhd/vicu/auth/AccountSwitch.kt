package com.rendyhd.vicu.auth

/** Who the stored session (or an incoming login) belongs to. [userId] is null when not known. */
data class AccountIdentity(val serverUrl: String, val userId: Long?)

/** What a login does to the data already on the device. */
enum class LoginDataAction {
    /** Same account again (for example a re-login after the session expired): keep everything. */
    KEEP,

    /** Different server or user, or no account on record: nothing local belongs to the new one. */
    WIPE_ALL,
}

object AccountSwitch {

    /**
     * Server URLs compared without cosmetic differences: surrounding whitespace, a trailing slash
     * and the case of the scheme and host. The path keeps its case, since a Vikunja instance can
     * live under one.
     */
    fun normalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return trimmed.lowercase()
        val pathStart = trimmed.indexOf('/', schemeEnd + 3).takeIf { it >= 0 } ?: trimmed.length
        return trimmed.substring(0, pathStart).lowercase() + trimmed.substring(pathStart)
    }

    /**
     * Decides what to do with local data when [incoming] signs in over [previous].
     *
     * Only a different server or a different known user is a different account. A previous
     * identity without a user id (a session stored before the id was recorded) on the same server
     * is treated as the same account, because re-authenticating the same account is by far the
     * common case and discarding its queued edits would be the worse mistake.
     */
    fun decide(previous: AccountIdentity?, incoming: AccountIdentity): LoginDataAction {
        if (previous == null || previous.serverUrl.isBlank()) return LoginDataAction.WIPE_ALL
        if (normalizeUrl(previous.serverUrl) != normalizeUrl(incoming.serverUrl)) return LoginDataAction.WIPE_ALL
        val previousUser = previous.userId
        if (previousUser != null && previousUser != incoming.userId) return LoginDataAction.WIPE_ALL
        return LoginDataAction.KEEP
    }
}

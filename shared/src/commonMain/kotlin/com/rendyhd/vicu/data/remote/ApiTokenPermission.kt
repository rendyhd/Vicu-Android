package com.rendyhd.vicu.data.remote

import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TimeSource
import io.ktor.util.AttributeKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An API token can lack a route group a collaboration request needs (task comments, task
 * assignees, project sharing; Vikunja offers no token the `subscriptions` group, so none may follow
 * a task), and the server answers that with 401, the same answer as an ended session. Such a 401
 * ends the session only when the same token is also refused the project list, which every token
 * the app works with reads; otherwise only that one request was refused (cross-app semantics 9.7).
 * [KtorClientFactory] asks, and marks such a call with [Refused]; [com.rendyhd.vicu.data.remote.api.VikunjaApiService]
 * turns it into a 403 that says what the token lacks.
 */
object ApiTokenPermission {
    const val TOKEN_LACKS_PERMISSION =
        "Your API token does not have this permission. In Vikunja, give the token the permissions for task comments, " +
            "task assignees and project sharing, or sign in with your password instead."
    const val TOKEN_CANNOT_FOLLOW = "Vikunja does not let API tokens follow tasks. Sign in with your password to use Notify me."

    /** How long a token that read the project list counts as working without asking again. */
    const val CHECK_FRESH_MS = 60_000L

    /** On a call answered 401 whose token still reads the project list: only this request was refused. */
    internal val Refused = AttributeKey<Unit>("VicuApiTokenPermissionRefused")
}

/**
 * Whether an API token still reads the project list. No answer counts as working: a network
 * problem says nothing about the token. A token that passed is not asked again for
 * [ApiTokenPermission.CHECK_FRESH_MS], because a members refresh can be refused once per shared
 * project, and refusals that arrive together share one check.
 */
class ApiTokenCheck(private val time: TimeSource = SystemTimeSource) {
    private val mutex = Mutex()
    private var workingToken: String? = null
    private var checkedAtMs = 0L

    /** [readProjects] asks the server and returns the HTTP status, or null when no answer came. */
    suspend fun readsProjects(token: String, readProjects: suspend () -> Int?): Boolean = mutex.withLock {
        val now = time.now().toEpochMilliseconds()
        if (workingToken == token && now - checkedAtMs < ApiTokenPermission.CHECK_FRESH_MS) return@withLock true
        val status = readProjects()
        val refused = status == 401 || status == 403
        if (status != null && status in 200..299) {
            workingToken = token
            checkedAtMs = now
        } else if (refused) {
            workingToken = null
        }
        !refused
    }
}

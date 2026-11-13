package com.rendyhd.vicu.util

import com.rendyhd.vicu.data.remote.api.TaskDto
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.time.Instant

/**
 * A repeating task completed on two devices is moved on once (docs/cross-app-semantics-v1.md,
 * section 7.5, vectors `repeatCompletion`).
 *
 * Vikunja moves a repeating task on from the dates it holds when `{ done: true }` arrives, so a
 * second completion of the same occurrence, sent later by a device that was offline or showed an
 * old copy, would skip an occurrence. A completion therefore remembers its **completed due** (the
 * due date the app held when the user completed the task); before it is sent the app reads the
 * task and decides with [decide].
 */
object RepeatCompletion {

    enum class Decision { SEND, SKIP }

    /**
     * Where a queued completion keeps its completed due in the payload JSON. Never sent: the
     * replay takes it out of the patch first ([fromQueued]).
     */
    const val COMPLETED_DUE_KEY = "vicu_completed_due"

    /** A task the app holds as repeating: `repeat_after` above 0 or `repeat_mode` not 0. */
    fun repeats(repeatAfter: Long, repeatMode: Int): Boolean = repeatAfter > 0L || repeatMode != 0

    /**
     * Whether the completion of the occurrence due at [completedDue] is still sent, given the
     * task as the server holds it now.
     */
    fun decide(
        completedDue: String?,
        serverDone: Boolean,
        serverDue: String?,
        serverRepeatAfter: Long,
        serverRepeatMode: Int,
    ): Decision = when {
        serverDone -> Decision.SKIP
        !repeats(serverRepeatAfter, serverRepeatMode) -> Decision.SEND
        sameInstant(completedDue, serverDue) -> Decision.SEND
        // Another device completed this occurrence already (or the date was changed since).
        else -> Decision.SKIP
    }

    /** [decide] against the task the server just answered with. */
    fun decide(completedDue: String?, server: TaskDto): Decision =
        decide(completedDue, server.done, server.dueDate, server.repeatAfter, server.repeatMode)

    /**
     * Two due dates compared as instants, never as text. `null`, an empty string and
     * `0001-01-01T00:00:00Z` are all "no date", and no date on both sides is the same.
     */
    fun sameInstant(a: String?, b: String?): Boolean {
        val noA = isNoDate(a)
        val noB = isNoDate(b)
        if (noA || noB) return noA && noB
        val ia = parse(a!!)
        val ib = parse(b!!)
        return if (ia != null && ib != null) ia == ib else a.trim() == b.trim()
    }

    private fun isNoDate(value: String?): Boolean {
        if (value.isNullOrBlank()) return true
        val instant = parse(value) ?: return value.trim() == Constants.NULL_DATE_STRING
        return instant == NULL_INSTANT
    }

    private val NULL_INSTANT: Instant = Instant.parse(Constants.NULL_DATE_STRING)

    private fun parse(value: String): Instant? = try {
        Instant.parse(value.trim())
    } catch (_: Exception) {
        null
    }

    /**
     * The completed due a completion of a task with these fields remembers, or null when the
     * completion needs no check: the task is not repeating as the app holds it, or the change is
     * not a completion. An empty string is "no date".
     */
    fun completedDueFor(done: Boolean, repeatAfter: Long, repeatMode: Int, dueDate: String?): String? =
        if (done && repeats(repeatAfter, repeatMode)) dueDate.orEmpty() else null

    /** [patch] with the completed due kept for the replay. */
    fun withCompletedDue(patch: JsonObject, completedDue: String): JsonObject =
        JsonObject(patch + (COMPLETED_DUE_KEY to JsonPrimitive(completedDue)))

    /**
     * A queued task change as stored: [patch] is what goes to the server (never the completed due
     * key), [completedDue] is non-null when the change completes a repeating task and has to be
     * checked first.
     */
    data class Queued(val patch: JsonObject, val completedDue: String?)

    /**
     * Splits a stored task patch. The check applies only to a completion (`done: true`) that
     * carries a completed due. A patch that also sets `due_date` is sent without the check: the
     * user chose the dates it moves on from. A payload without the key (written by an older
     * version, or a change that was not a completion) is sent as before.
     */
    fun fromQueued(stored: JsonObject): Queued {
        val raw = stored[COMPLETED_DUE_KEY] ?: return Queued(stored, null)
        val patch = JsonObject(stored - COMPLETED_DUE_KEY)
        val completes = (patch["done"] as? JsonPrimitive)?.booleanOrNull == true
        if (!completes || "due_date" in patch) return Queued(patch, null)
        val due = when (raw) {
            is JsonNull -> ""
            is JsonPrimitive -> raw.content
            else -> ""
        }
        return Queued(patch, due)
    }

    /** What else a skipped completion still sends: the fields edited together with it. */
    fun withoutCompletion(patch: JsonObject): JsonObject = JsonObject(patch - "done" - "done_at")
}

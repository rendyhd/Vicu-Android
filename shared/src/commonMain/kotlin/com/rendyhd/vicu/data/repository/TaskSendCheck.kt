package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.remote.api.TaskDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.util.NotesMerge
import com.rendyhd.vicu.util.RepeatCompletion
import com.rendyhd.vicu.util.isRetriableNetworkError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The read some changes of an existing task need before they are sent, at once or from the queue:
 * the completion of a repeating task (cross-app semantics 7.5, [RepeatCompletion]) and a notes edit
 * that knows the notes it started from (description format, "Notes changed in two places",
 * [NotesMerge]). When a change needs both, the task is read once.
 */
object TaskSendCheck {

    /**
     * What to send after the read. An empty [patch] sends nothing: the task as [server] holds it is
     * the result ([server] is then never null).
     */
    class Plan(
        val patch: JsonObject,
        /** The task as just read, when it was. */
        val server: TaskDto?,
        /** Another device completed this occurrence already: the completion was taken out. */
        val completionSkipped: Boolean,
        /** The notes were merged: the app says so once ([NotesMerge.MERGED_MESSAGE]). */
        val notesMerged: Boolean,
    ) {
        val sendsNothing: Boolean get() = patch.isEmpty()
    }

    /**
     * Plans the send of [patch] (never holding the queue's own keys) to task [taskId]. [completedDue]
     * is the due date a completion of a repeating task holds, [notesBase] the notes a notes edit
     * started from; with neither nothing is read.
     *
     * A read that fails because the server cannot be reached throws, so the change waits in the
     * queue. Any other failed read throws when a completion needs it (the send's caller refuses or
     * keeps it, as before) and otherwise sends the notes as they are.
     */
    suspend fun plan(
        api: VikunjaApiService,
        taskId: Long,
        patch: JsonObject,
        completedDue: String?,
        notesBase: String?,
    ): Plan {
        val checksNotes = notesBase != null && "description" in patch
        if (completedDue == null && !checksNotes) return Plan(patch, null, completionSkipped = false, notesMerged = false)
        val server = try {
            api.getTask(taskId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (completedDue != null || isRetriableNetworkError(e)) throw e
            return Plan(patch, null, completionSkipped = false, notesMerged = false)
        }
        var out = patch
        val skipped = completedDue != null &&
            RepeatCompletion.decide(completedDue, server) == RepeatCompletion.Decision.SKIP
        if (skipped) out = RepeatCompletion.withoutCompletion(out)
        var merged = false
        if (checksNotes) {
            val decision = NotesMerge.decideNotesSave(notesBase, descriptionOf(out), server.description)
            out = NotesMerge.applyTo(out, decision)
            merged = decision is NotesMerge.Decision.Merge
        }
        return Plan(out, server, completionSkipped = skipped, notesMerged = merged)
    }

    private fun descriptionOf(patch: JsonObject): String? {
        val value = patch["description"] ?: return null
        if (value is JsonNull) return null
        return (value as? JsonPrimitive)?.content
    }
}

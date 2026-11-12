package com.rendyhd.vicu.util.pro

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A meeting round: where it stands. [ME] (0) is your own card in the queue. */
data class RoundState(
    /** The meeting's people in order; [Meeting.ME] (0) for your own card. */
    val queue: List<Long>,
    val current: Long?,
    val discussed: List<Long> = emptyList(),
    val skipped: List<Long> = emptyList(),
    val absent: List<Long> = emptyList(),
)

/**
 * A meeting round (docs/cross-app-semantics-v1.md section 21.9), the same as the desktop's
 * `src/shared/pro/meeting.ts`: the people of a meeting in any order, skipping allowed, no timer
 * (decision 12). Pure: every step takes the state and returns the next.
 */
object Meeting {
    /** Your own card in the round: promises you owe the room. */
    const val ME = 0L

    private fun List<Long>.without(person: Long): List<Long> = filter { it != person }

    fun startRound(people: List<Long>, withMe: Boolean = true): RoundState {
        val queue = people.filter { it != ME }.distinct().toMutableList()
        if (withMe) queue.add(ME)
        return RoundState(queue, queue.firstOrNull())
    }

    /** The next person after [from] in queue order (wrapping) not yet discussed, skipped or absent. */
    private fun nextAfter(state: RoundState, from: Long?): Long? {
        val done = (state.discussed + state.skipped + state.absent).toSet()
        val start = if (from == null) -1 else state.queue.indexOf(from)
        for (step in 1..state.queue.size) {
            val candidate = state.queue[((start + step) % state.queue.size + state.queue.size) % state.queue.size]
            if (candidate !in done) return candidate
        }
        return null
    }

    /** Jump to anyone; a skipped or absent person picked again is back in the round. */
    fun pickPerson(state: RoundState, person: Long): RoundState {
        if (person !in state.queue) return state
        return state.copy(
            current = person,
            skipped = state.skipped.without(person),
            absent = state.absent.without(person),
            discussed = state.discussed.without(person),
        )
    }

    /** Done with the current person: discussed, then on to the next. */
    fun nextPerson(state: RoundState): RoundState {
        val current = state.current ?: return state
        val next = state.copy(discussed = state.discussed.without(current) + current)
        return next.copy(current = nextAfter(next, current))
    }

    /** Skip the current person for now; picking them later brings them back. */
    fun skipPerson(state: RoundState): RoundState {
        val current = state.current ?: return state
        val next = state.copy(skipped = state.skipped.without(current) + current)
        return next.copy(current = nextAfter(next, current))
    }

    /** Mark someone absent; when it is the current person, on to the next. */
    fun markAbsent(state: RoundState, person: Long): RoundState {
        if (person !in state.queue) return state
        val next = state.copy(
            absent = state.absent.without(person) + person,
            skipped = state.skipped.without(person),
            discussed = state.discussed.without(person),
        )
        return if (state.current == person) next.copy(current = nextAfter(next, person)) else next
    }

    /**
     * The round record when the meeting ends: discussed (the current person counts when the meeting
     * ends on them), skipped, absent, and not discussed (everyone else). Lists keep queue order; your
     * own card is left out of every list. [at] is a local day.
     */
    fun endRound(state: RoundState, meetingId: String, at: String): JsonObject {
        val final = if (state.current == null) state else nextPerson(state)
        fun inQueue(ids: List<Long>) = state.queue.filter { it != ME && it in ids }
        val seen = (final.discussed + final.skipped + final.absent).toSet()
        fun array(ids: List<Long>) = JsonArray(ids.map(::JsonPrimitive))
        return JsonObject(
            mapOf(
                "meeting" to JsonPrimitive(meetingId),
                "at" to JsonPrimitive(at),
                "discussed" to array(inQueue(final.discussed)),
                "skipped" to array(inQueue(final.skipped)),
                "absent" to array(inQueue(final.absent)),
                "not_discussed" to array(state.queue.filter { it != ME && it !in seen }),
            ),
        )
    }
}

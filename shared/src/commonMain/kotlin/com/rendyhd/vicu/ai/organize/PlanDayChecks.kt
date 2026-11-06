package com.rendyhd.vicu.ai.organize

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.dictation.DictationChecks
import com.rendyhd.vicu.util.LeftoverAction
import com.rendyhd.vicu.util.MinuteRange
import com.rendyhd.vicu.util.TimeBlocks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** A leftover's suggested action, with why. */
data class PlanLeftover(val taskId: Long, val action: LeftoverAction, val reason: String)

/** A pick. A null [minutes] takes the length the task would have anyway, a null [start] the next free slot. */
data class PlanPick(val taskId: Long, val minutes: Int?, val start: String?, val reason: String)

data class PlanCanWait(val taskId: Long, val reason: String)

/** Something the checks left out: [field] is `task_id`, `action`, `limit`, `minutes` or `start`. */
data class PlanDropped(val taskId: Long?, val field: String, val value: JsonElement)

/** A checked answer (`docs/ai-assistant-v1.md`, section 10.3). */
data class PlanDayResult(
    val leftovers: List<PlanLeftover>,
    val picks: List<PlanPick>,
    val canWait: List<PlanCanWait>,
    val dropped: List<PlanDropped>,
)

/** What the answer is checked against. [free] holds `HH:MM` pairs. */
data class PlanDayCheckInput(
    val free: List<Pair<String, String>>,
    val defaultLength: Int,
    val leftovers: List<Long>,
    val candidates: List<Long>,
)

/**
 * The checks of `docs/ai-assistant-v1.md`, section 10.3: anything that fails is dropped, the rest
 * stands. The same as the desktop's `src/shared/ai/plan-day.ts`; vectors: `planDay.checks`.
 */
object PlanDayChecks {
    private val limits get() = AiContract.limits
    private val clock = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")

    /** Free ranges in minutes as the request and the checks spell them. */
    fun freeAsClock(ranges: List<MinuteRange>): List<Pair<String, String>> =
        ranges.map { TimeBlocks.clockText(it.start) to TimeBlocks.clockText(it.end) }

    fun check(answer: JsonObject, input: PlanDayCheckInput): PlanDayResult {
        val dropped = mutableListOf<PlanDropped>()
        val free = input.free.map { (a, b) -> TimeBlocks.clockMinutes(a) to TimeBlocks.clockMinutes(b) }

        // 1. Leftovers.
        val leftoverIds = input.leftovers.toSet()
        val seenLeftovers = HashSet<Long>()
        val leftovers = mutableListOf<PlanLeftover>()
        for (item in entries(answer["leftovers"])) {
            val id = wholeNumber(item["task_id"])
            if (id == null || id !in leftoverIds || id in seenLeftovers) {
                dropped += PlanDropped(id, "task_id", item["task_id"] ?: JsonNull)
                continue
            }
            val action = LeftoverAction.of((item["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
            if (action == null) {
                dropped += PlanDropped(id, "action", item["action"] ?: JsonNull)
                continue
            }
            seenLeftovers += id
            leftovers += PlanLeftover(id, action, reasonOf(item["reason"]))
        }

        // 2 to 4. Picks, their minutes and their starts.
        val candidateIds = input.candidates.toSet()
        val seenPicks = HashSet<Long>()
        val taken = mutableListOf<Pair<Int, Int>>()
        val picks = mutableListOf<PlanPick>()
        for (item in entries(answer["picks"])) {
            val id = wholeNumber(item["task_id"])
            if (id == null || id !in candidateIds || id in seenPicks) {
                dropped += PlanDropped(id, "task_id", item["task_id"] ?: JsonNull)
                continue
            }
            if (picks.size >= limits.planDayPicksMax) {
                dropped += PlanDropped(id, "limit", JsonPrimitive(id))
                continue
            }
            seenPicks += id

            val given = wholeNumber(item["minutes"])
            var minutes: Int? = null
            if (given != null && given >= limits.planDayMinutesMin && given <= limits.planDayMinutesMax) {
                val step = TimeBlocks.STEP_MINUTES
                minutes = ((given.toInt() + step - 1) / step) * step
            } else {
                dropped += PlanDropped(id, "minutes", item["minutes"] ?: JsonNull)
            }

            var start: String? = null
            val rawStart = item["start"]
            if (rawStart != null && rawStart !is JsonNull) {
                val length = minutes ?: input.defaultLength
                val text = (rawStart as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                val from = text?.takeIf(clock::matches)?.let(TimeBlocks::clockMinutes)
                if (from != null &&
                    free.any { (a, b) -> from >= a && from + length <= b } &&
                    taken.none { (a, b) -> from < b && from + length > a }
                ) {
                    start = text
                    taken += from to from + length
                } else {
                    dropped += PlanDropped(id, "start", rawStart)
                }
            }

            picks += PlanPick(id, minutes, start, reasonOf(item["reason"]))
        }

        // 5. What can wait.
        val seenWait = HashSet<Long>()
        val canWait = mutableListOf<PlanCanWait>()
        for (item in entries(answer["can_wait"])) {
            val id = wholeNumber(item["task_id"])
            if (id == null || id !in candidateIds || id in seenPicks || id in seenWait) {
                dropped += PlanDropped(id, "task_id", item["task_id"] ?: JsonNull)
                continue
            }
            seenWait += id
            canWait += PlanCanWait(id, reasonOf(item["reason"]))
        }

        return PlanDayResult(leftovers, picks, canWait, dropped)
    }

    private fun entries(value: JsonElement?): List<JsonObject> =
        (value as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    /** A JSON number without a fraction; a string with digits is not one. */
    private fun wholeNumber(value: JsonElement?): Long? {
        val primitive = (value as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull } ?: return null
        return primitive.longOrNull ?: primitive.doubleOrNull?.takeIf { it % 1.0 == 0.0 }?.toLong()
    }

    private fun reasonOf(value: JsonElement?): String {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString && it !is JsonNull }?.content ?: return ""
        return OrganizeChecks.cut(DictationChecks.oneLine(text).trim(), limits.organizeReasonMaxChars)
    }
}

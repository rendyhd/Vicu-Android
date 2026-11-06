package com.rendyhd.vicu.ai.organize

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.AiProviders
import com.rendyhd.vicu.ai.AiSettingsStore
import com.rendyhd.vicu.ai.AiText
import com.rendyhd.vicu.ai.ask.AskBackend
import com.rendyhd.vicu.ai.dictation.DictationContext
import com.rendyhd.vicu.ai.dictation.DictationRequests
import com.rendyhd.vicu.ai.dictation.DictationService
import com.rendyhd.vicu.ai.provider.ChatMessage
import com.rendyhd.vicu.ai.provider.ChatRequest
import com.rendyhd.vicu.ai.provider.LlmException
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.MinuteRange
import com.rendyhd.vicu.util.TimeBlocks
import com.rendyhd.vicu.voice.TaskDefaults
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** One request for a session's suggestions: everything is the day as it was read just before it. */
data class PlanDayRequest(
    val day: LocalDate,
    /** The time of the request, in minutes from local midnight. */
    val nowMinute: Int,
    val defaultLength: Int,
    /** The free time of the day (semantics 16.6). */
    val free: List<MinuteRange>,
    /** The routine times of the day. No names leave the app. */
    val routineMinutes: List<Int>,
    val leftovers: List<Task>,
    val candidates: List<Task>,
)

/** The text of the request (`docs/ai-assistant-v1.md`, section 10.1), the same as the desktop sends. */
object PlanDayRequests {
    /** The context's `list` (section 10.1). */
    const val LIST_NAME = "Plan"

    fun system(): String = AiContract.prompt("planDay")

    /** The `<plan>` block. */
    fun planBlock(request: PlanDayRequest): JsonObject = buildJsonObject {
        put("day", request.day.toString())
        put("now", TimeBlocks.clockText(request.nowMinute))
        put("default_length", request.defaultLength)
        putJsonArray("free") {
            for (range in request.free) {
                add(
                    buildJsonObject {
                        put("start", TimeBlocks.clockText(range.start))
                        put("end", TimeBlocks.clockText(range.end))
                    },
                )
            }
        }
        putJsonArray("routines") {
            for (minute in request.routineMinutes.sorted()) add(TimeBlocks.clockText(minute))
        }
    }

    /** One task of `<leftovers>` or `<candidates>`: its summary (5.2), then `notes`. */
    fun taskEntry(summary: JsonObject, notes: String): JsonObject =
        JsonObject(summary + ("notes" to JsonPrimitive(OrganizeRequests.notes(notes))))

    fun userMessage(context: DictationContext, plan: JsonObject, leftovers: List<JsonObject>, candidates: List<JsonObject>): String = buildString {
        append("<context>\n").append(DictationRequests.contextJson(context)).append("\n</context>\n")
        append("<plan>\n").append(plan.toString()).append("\n</plan>\n")
        append("<leftovers>\n").append(JsonArray(leftovers).toString()).append("\n</leftovers>\n")
        append("<candidates>\n").append(JsonArray(candidates).toString()).append("\n</candidates>")
    }
}

/** Where a Plan my day session gets its suggestions (`docs/ai-assistant-v1.md`, section 10). */
interface PlanDaySuggester {
    /** Suggestions can be asked for: Ask is set up, switched on and agreed to. */
    suspend fun isReady(): Boolean

    /** The checked answer for [request]. Throws when the request fails. */
    suspend fun suggest(request: PlanDayRequest): PlanDayResult
}

/** One structured request with Ask's model, under Ask's agreement, checked by [PlanDayChecks]. */
class PlanDayService(
    private val providers: AiProviders,
    private val settings: AiSettingsStore,
    private val backend: AskBackend,
    private val dictation: DictationService,
) : PlanDaySuggester {
    override suspend fun isReady(): Boolean = settings.current().askReady

    /** Throws [LlmException] when the request fails. */
    override suspend fun suggest(request: PlanDayRequest): PlanDayResult {
        val s = settings.current()
        val connection = providers.connection(s) ?: throw LlmException(LlmException.Kind.BAD_REQUEST, "Set up the assistant first.")
        val snapshot = backend.snapshot()
        val context = dictation.context(TaskDefaults(listName = PlanDayRequests.LIST_NAME))
        val max = AiContract.limits.planDayCandidatesMax
        val leftovers = request.leftovers.take(max)
        val candidates = request.candidates.take(max)
        fun entry(task: Task) = PlanDayRequests.taskEntry(snapshot.summary(task), AiText.htmlToPlain(task.description))
        val answer = providers.structured.request(
            connection,
            ChatRequest(
                model = s.askModel,
                system = PlanDayRequests.system(),
                messages = listOf(
                    ChatMessage.User(
                        PlanDayRequests.userMessage(context, PlanDayRequests.planBlock(request), leftovers.map(::entry), candidates.map(::entry)),
                    ),
                ),
                schema = AiContract.planDayAnswerSchema,
                structuredMode = s.structuredMode(s.askModel),
                deterministic = true,
                idleTimeoutSeconds = AiContract.limits.dictationIdleTimeoutSeconds,
                maxTokens = AiContract.limits.planDayMaxTokens,
            ),
        )
        return PlanDayChecks.check(
            answer,
            PlanDayCheckInput(
                free = PlanDayChecks.freeAsClock(request.free),
                defaultLength = request.defaultLength,
                leftovers = leftovers.map { it.id },
                candidates = candidates.map { it.id },
            ),
        )
    }
}

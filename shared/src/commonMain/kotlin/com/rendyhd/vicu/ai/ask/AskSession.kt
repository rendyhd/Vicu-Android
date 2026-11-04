package com.rendyhd.vicu.ai.ask

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.AiProviders
import com.rendyhd.vicu.ai.AiSettingsStore
import com.rendyhd.vicu.ai.AskToolSpec
import com.rendyhd.vicu.ai.JsonSchema
import com.rendyhd.vicu.ai.dictation.DictationContext
import com.rendyhd.vicu.ai.provider.ChatEvent
import com.rendyhd.vicu.ai.provider.ChatMessage
import com.rendyhd.vicu.ai.provider.ChatRequest
import com.rendyhd.vicu.ai.provider.LlmException
import com.rendyhd.vicu.ai.provider.ToolCall
import com.rendyhd.vicu.ai.provider.ToolResult
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The list the user is in when they ask (section 5.1). */
data class AskList(
    val name: String,
    val projectId: Long? = null,
    val customListId: String? = null,
)

/** What the session reads from the app. */
interface AskBackend {
    suspend fun snapshot(): AskSnapshot

    suspend fun logbook(): List<Task>

    /** The dictation context for `propose_create_tasks`, in [list]. */
    suspend fun dictationContext(list: AskList): DictationContext
}

/** A proposal card waiting for the user. */
data class PendingProposal(val proposal: Proposal, val snapshot: AskSnapshot)

data class AskState(
    val conversation: Conversation,
    /** The answer as it streams in. */
    val streaming: String = "",
    val busy: Boolean = false,
    /** What the assistant is doing between answers ("Looking at your tasks"). */
    val activity: String? = null,
    val pending: PendingProposal? = null,
    /**
     * A card the user wrote past (section 6.1): its call was answered declined and the words went on,
     * but the card stays and can still be applied until a new card comes.
     */
    val lingering: PendingProposal? = null,
    val error: String? = null,
    /** The person stopped the last turn; the next one clears it. */
    val stopped: Boolean = false,
)

/**
 * One Ask (or Talk) conversation with the user's model (`docs/ai-assistant-v1.md`, section 5): the
 * context block, the streamed answers, the read tools, proposal cards that wait for the user, at
 * most 8 tool rounds per message, and the conversation kept on the device.
 */
class AskSession(
    private val providers: AiProviders,
    private val settings: AiSettingsStore,
    private val backend: AskBackend,
    private val proposals: Proposals,
    private val store: ConversationStore,
    conversation: Conversation,
    private val time: TimeSource = SystemTimeSource,
    private val newId: () -> String,
) {
    private val _state = MutableStateFlow(AskState(conversation))
    val state: StateFlow<AskState> = _state.asStateFlow()

    private var decision: CompletableDeferred<Applied>? = null
    /** The user wrote while a card was open: the round that waited for it ends after its results. */
    private var interrupted = false
    private val json = Json { ignoreUnknownKeys = true }

    val conversation: Conversation get() = _state.value.conversation

    /** Sends a user message and runs the turn until the model answers. [list] is where the user is. */
    suspend fun send(text: String, list: AskList, talk: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _state.value.pending?.let { open ->
            // Written past an open card (section 6.1): the call is answered declined, the card stays.
            interrupted = true
            _state.update { it.copy(pending = null, lingering = open) }
            decision?.complete(Applied.declined(open.proposal.skipped))
            state.first { !it.busy }
        }
        interrupted = false
        if (_state.value.busy) return
        val snapshot = backend.snapshot()
        val now = time.now()
        val local = now.toLocalDateTime(snapshot.zone)
        val c = conversation
        val first = c.messages.none { it is ChatMessage.User }
        val refresh = !first && (c.lastDay != local.date.toString() ||
            now.toEpochMilliseconds() - c.lastSentAt > AiContract.limits.contextRefreshMinutes * 60_000L)
        val body = when {
            first -> contextBlock(snapshot, local, list, full = true) + trimmed
            refresh -> contextBlock(snapshot, local, list, full = false) + trimmed
            else -> trimmed
        }
        update {
            it.copy(
                title = it.title.ifEmpty { trimmed.take(60) },
                messages = it.messages + ChatMessage.User(body),
                lastSentAt = now.toEpochMilliseconds(),
                lastDay = local.date.toString(),
                updatedAt = now.toEpochMilliseconds(),
                talk = it.talk || talk,
            )
        }
        run(list, talk)
    }

    /**
     * Talking again cancelled the turn (`docs/ai-assistant-v1.md`, section 3.7): the conversation goes
     * back to its first [size] messages. Its words come again with the next turn.
     */
    suspend fun withdraw(size: Int) {
        if (conversation.messages.size <= size) return
        update { c ->
            val kept = c.messages.take(size)
            c.copy(messages = kept, title = if (kept.none { it is ChatMessage.User }) "" else c.title)
        }
        _state.update { it.copy(error = null, stopped = false) }
        store.save(conversation)
    }

    /**
     * The person stopped the turn, and the job running it has ended. What they asked stays in the
     * conversation, and a tool call left without its result gets one, so the next request is valid.
     */
    suspend fun stopped() {
        val last = conversation.messages.lastOrNull()
        if (last is ChatMessage.Assistant && last.toolCalls.isNotEmpty()) {
            val results = last.toolCalls.map {
                ToolResult(it.id, it.name, error("cancelled", "the user stopped this"), isError = true)
            }
            update { c -> c.copy(messages = c.messages + ChatMessage.ToolResults(results)) }
        }
        _state.update { it.copy(error = null, stopped = true) }
        store.save(conversation)
    }

    /** Runs the model again after a failed request. */
    suspend fun retry(list: AskList, talk: Boolean) {
        if (_state.value.busy) return
        val last = conversation.messages.lastOrNull()
        if (last is ChatMessage.Assistant && last.toolCalls.isNotEmpty()) {
            update { it.copy(messages = it.messages.dropLast(1)) }
        }
        if (conversation.messages.lastOrNull() !is ChatMessage.User && conversation.messages.lastOrNull() !is ChatMessage.ToolResults) return
        run(list, talk)
    }

    /** The user answered the open card. Undo is the caller's to offer. */
    fun decide(applied: Applied) {
        decision?.complete(applied)
    }

    /**
     * The user answered a lingering card. The model was told it was declined, so only the
     * conversation's record of it changes.
     */
    suspend fun decideLingering(applied: Applied) {
        val card = _state.value.lingering ?: return
        _state.update { it.copy(lingering = null) }
        if (applied.status == "declined") return
        record(card.proposal.callId, applied, card.proposal)
        remember(applied.appliedIds)
        store.save(conversation)
    }

    private suspend fun run(list: AskList, talk: Boolean) {
        val s = settings.current()
        val connection = providers.connection(s)
        if (connection == null || !s.askReady) {
            _state.update { it.copy(error = "Set up Ask in the assistant settings first.") }
            return
        }
        _state.update { it.copy(busy = true, error = null, stopped = false, streaming = "") }
        val system = AiContract.prompt("ask") + if (talk) "\n\n" + AiContract.prompt("talk") else ""
        val tools = AiContract.askTools.filter { it.enabled }
        var rounds = 0
        try {
            while (true) {
                val final = rounds >= AiContract.limits.askToolRoundsMax
                if (final) update { it.copy(messages = it.messages + ChatMessage.User(AiContract.message("finalAnswer"))) }
                val request = ChatRequest(
                    model = s.askModel,
                    system = system,
                    messages = window(conversation.messages),
                    tools = tools,
                    idleTimeoutSeconds = AiContract.limits.askIdleTimeoutSeconds,
                    maxTokens = ASK_MAX_TOKENS,
                    toolsDisabled = final,
                )
                var finished: ChatEvent.Finished? = null
                providers.llm.stream(connection, request).collect { event ->
                    when (event) {
                        is ChatEvent.TextDelta -> _state.update { it.copy(streaming = it.streaming + event.text, activity = null) }
                        is ChatEvent.Finished -> finished = event
                    }
                }
                val answer = finished?.message ?: throw LlmException(LlmException.Kind.BAD_ANSWER, "The answer ended early.")
                val calls = if (final) emptyList() else answer.toolCalls
                update { it.copy(messages = it.messages + answer.copy(toolCalls = calls).let { a -> if (final) a.copy(raw = null) else a }) }
                _state.update { it.copy(streaming = "") }
                if (calls.isEmpty()) break

                val results = mutableListOf<ToolResult>()
                for (call in calls) results += runTool(call, tools, list)
                update { it.copy(messages = it.messages + ChatMessage.ToolResults(results)) }
                // The user wrote past a card: their message is the next turn.
                if (interrupted) break
                rounds++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            _state.update { it.copy(error = e.message ?: "The model did not answer.") }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Something went wrong.") }
        } finally {
            _state.update { it.copy(busy = false, streaming = "", activity = null, pending = null) }
            store.save(conversation)
        }
    }

    private suspend fun runTool(call: ToolCall, tools: List<AskToolSpec>, list: AskList): ToolResult {
        val spec = tools.firstOrNull { it.name == call.name }
            ?: return ToolResult(call.id, call.name, error("invalid_arguments", "unknown tool ${call.name}"), isError = true)
        val args = runCatching { json.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject }.getOrNull()
            ?: return ToolResult(call.id, call.name, error("invalid_arguments", "the arguments are not a JSON object"), isError = true)
        JsonSchema.firstProblem(args, spec.parameters)?.let { problem ->
            return ToolResult(call.id, call.name, error("invalid_arguments", problem), isError = true)
        }
        _state.update { it.copy(activity = if (spec.isProposal) null else ACTIVITY_LOOKING) }
        val snapshot = backend.snapshot()
        if (!spec.isProposal) {
            val result: JsonObject = when (call.name) {
                "get_overview" -> snapshot.overview()
                "find_tasks" -> snapshot.findTasks(args)
                "list_view" -> snapshot.listView(args, if ((args["view"] as? JsonPrimitive)?.content == "logbook") backend.logbook() else emptyList())
                "get_task" -> snapshot.getTask(args)
                else -> JsonObject(mapOf("error" to JsonPrimitive("invalid_arguments")))
            }
            remember(result)
            return ToolResult(call.id, call.name, result.toString(), isError = "error" in result)
        }
        val context = backend.dictationContext(list)
        return when (val built = proposals.build(call.name, call.id, args, snapshot, context)) {
            is Proposals.Built.Error -> ToolResult(call.id, call.name, error("invalid_arguments", built.detail), isError = true)
            is Proposals.Built.Ok -> {
                val proposal = built.proposal
                if (proposal.size == 0) {
                    val applied = Applied("failed", emptyList(), proposal.skipped, null)
                    record(call.id, applied, proposal)
                    return ToolResult(call.id, call.name, applied.toolResult())
                }
                if (interrupted) {
                    // A second card of the round the user wrote past is declined unseen.
                    val declined = Applied.declined(proposal.skipped)
                    record(call.id, declined, proposal)
                    return ToolResult(call.id, call.name, declined.toolResult())
                }
                val wait = CompletableDeferred<Applied>()
                decision = wait
                // An older card the user could still apply closes when a new one comes.
                _state.update { it.copy(pending = PendingProposal(proposal, snapshot), lingering = null, activity = null) }
                val applied = wait.await()
                decision = null
                _state.update { it.copy(pending = null) }
                record(call.id, applied, proposal)
                remember(applied.appliedIds)
                ToolResult(call.id, call.name, applied.toolResult())
            }
        }
    }

    private fun record(callId: String, applied: Applied, proposal: Proposal) {
        val summary = when (proposal) {
            is Proposal.Create -> proposal.result.drafts.joinToString(", ") { it.title }
            is Proposal.Update -> proposal.changes.joinToString(", ") { it.before.title }
            is Proposal.Complete -> proposal.tasks.joinToString(", ") { it.title }
        }
        update { it.copy(outcomes = it.outcomes + (callId to ProposalOutcome(applied.status, applied.appliedIds.size.coerceAtLeast(proposal.size), summary.take(300)))) }
    }

    /** Task ids a tool returned, so `[task:id]` renders as a row. */
    private fun remember(result: JsonElement) {
        val ids = HashSet<Long>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    if ("title" in e && "done" in e) (e["id"] as? JsonPrimitive)?.longOrNull?.let { ids += it }
                    e.values.forEach(::walk)
                }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(result)
        remember(ids.toList())
    }

    private fun remember(ids: List<Long>) {
        if (ids.isEmpty()) return
        update { it.copy(knownTaskIds = it.knownTaskIds + ids) }
    }

    private fun contextBlock(snapshot: AskSnapshot, local: kotlinx.datetime.LocalDateTime, list: AskList, full: Boolean): String {
        val o = buildJsonObject {
            put("now", "${local.date}T${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}")
            put("weekday", local.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() })
            if (full) {
                put("time_zone", snapshot.zone.id)
                put("week_starts", "monday")
                put("list", list.name.ifBlank { list.projectId?.let { snapshot.path(it) } ?: "Inbox" })
                list.projectId?.let { put("list_project_id", it) }
                list.customListId?.let { put("list_custom_list_id", it) }
                put("overview", snapshot.overview())
            }
        }
        if (full) remember(o)
        return "<context>\n$o\n</context>\n"
    }

    private fun update(change: (Conversation) -> Conversation) {
        _state.update { it.copy(conversation = change(it.conversation)) }
    }

    private fun error(code: String, detail: String) = buildJsonObject {
        put("error", code)
        put("detail", detail)
    }.toString()

    companion object {
        const val ASK_MAX_TOKENS = 16_000
        const val ACTIVITY_LOOKING = "Looking at your tasks"

        /** The messages a request sends: the last 40, starting at a user message (section 5.1). */
        fun window(messages: List<ChatMessage>): List<ChatMessage> =
            ConversationStore.trim(messages, AiContract.limits.requestMaxMessages)

        /** Strips the context block from a stored user message, for display. */
        fun visibleText(message: String): String =
            if (message.startsWith("<context>")) message.substringAfter("</context>\n", message) else message

        /** `[task:123]` marks in model text (section 5.1). */
        val TASK_MARK = Regex("""\[task:(\d+)]""")

        @Suppress("unused")
        private fun today(zone: TimeZone, time: TimeSource): LocalDate = time.now().toLocalDateTime(zone).date
    }
}

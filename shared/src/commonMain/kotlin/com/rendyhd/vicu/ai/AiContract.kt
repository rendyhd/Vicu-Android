package com.rendyhd.vicu.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The AI assistant contract (`docs/ai-assistant-v1.md`) as the app reads it: the text of
 * `test-fixtures/ai-tools-v1.json`, carried in the app by a generated constant, so the prompts,
 * schemas, tools, presets and limits sent are exactly the fixture's, the same as on the desktop.
 */
object AiContract {
    val root: JsonObject by lazy { Json.parseToJsonElement(AiContractSource.json).jsonObject }

    val contractVersion: Int get() = root.getValue("contractVersion").jsonPrimitive.int

    val limits: AiLimits by lazy { AiLimits(root.obj("limits")) }

    val providers: List<ProviderPreset> by lazy {
        root.getValue("providers").jsonArray.map { ProviderPreset.from(it.jsonObject) }
    }

    fun provider(id: String): ProviderPreset? = providers.firstOrNull { it.id == id }

    /** The chat and models paths of each wire protocol. */
    fun protocolPath(protocol: String, kind: String): String =
        root.obj("protocols").obj(protocol).str(kind)

    val probe: JsonObject get() = root.obj("probe")

    val speech: JsonObject get() = root.obj("speech")

    val dictationAnswerSchema: JsonObject get() = root.obj("dictation").obj("answerSchema")

    /** The brain dump's answer: the dictation answer with new projects (section 4.8.2). */
    val brainDumpAnswerSchema: JsonObject get() = root.obj("brainDump").obj("answerSchema")

    /** The answer schema of a guided session's suggestions (section 9.2). */
    val organizeAnswerSchema: JsonObject get() = root.obj("organize").obj("answerSchema")

    /** Plan my day's answer (section 10.2). */
    val planDayAnswerSchema: JsonObject get() = root.obj("planDay").obj("answerSchema")

    /** Ask's starters by list kind (section 5.7): `today`, `upcoming`, `inbox`, `project`, `other`. */
    fun starters(kind: String): List<String> =
        (root.obj("ask").obj("starters")[kind] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    /** The draft fields in the order `guessed` lists them. */
    val dictationFields: List<String> by lazy { root.obj("dictation").getValue("fields").jsonArray.map { it.jsonPrimitive.content } }

    val askTools: List<AskToolSpec> by lazy {
        root.obj("ask").getValue("tools").jsonArray.map { element ->
            val o = element.jsonObject
            AskToolSpec(
                name = o.str("name"),
                kind = o.str("kind"),
                enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                description = o.str("description"),
                parameters = o.obj("parameters"),
            )
        }
    }

    /** The English (and any other language's) yes and no phrases of section 6.1. */
    val confirmPhrases: Map<String, Pair<Set<String>, Set<String>>> by lazy {
        root.obj("talk").obj("confirm").mapValues { (_, value) ->
            val o = value.jsonObject
            o.getValue("yes").jsonArray.map { it.jsonPrimitive.content }.toSet() to
                o.getValue("no").jsonArray.map { it.jsonPrimitive.content }.toSet()
        }
    }

    /** A prompt's text: its lines joined with `\n` (section 8). */
    fun prompt(name: String): String =
        root.obj("prompts").obj(name).getValue("lines").jsonArray.joinToString("\n") { it.jsonPrimitive.content }

    fun promptVersion(name: String): Int = root.obj("prompts").obj(name).getValue("version").jsonPrimitive.int

    fun message(name: String): String = root.obj("messages").str(name)
}

data class AskToolSpec(
    val name: String,
    /** `read` or `proposal`. */
    val kind: String,
    val enabled: Boolean,
    val description: String,
    val parameters: JsonObject,
) {
    val isProposal: Boolean get() = kind == "proposal"
}

/** The numbers of the `limits` block. */
class AiLimits(private val o: JsonObject) {
    private fun n(key: String): Int = o.getValue(key).jsonPrimitive.int

    val audioMaxSeconds get() = n("audio_max_seconds")
    val transcriptMaxChars get() = n("transcript_max_chars")
    val draftsMax get() = n("drafts_max")
    val titleMaxChars get() = n("title_max_chars")
    val notesMaxChars get() = n("notes_max_chars")
    val labelsMax get() = n("labels_max")
    val subtasksMax get() = n("subtasks_max")
    val subtaskTitleMaxChars get() = n("subtask_title_max_chars")
    val repeatEveryMax get() = n("repeat_every_max")
    val reminderBeforeDueMaxMinutes get() = n("reminder_before_due_max_minutes")
    val modelDateMaxYears get() = n("model_date_max_years")
    val repairAttempts get() = n("repair_attempts")
    val connectTimeoutSeconds get() = n("connect_timeout_s")
    val dictationIdleTimeoutSeconds get() = n("dictation_idle_timeout_s")
    val askIdleTimeoutSeconds get() = n("ask_idle_timeout_s")
    val askToolRoundsMax get() = n("ask_tool_rounds_max")
    val findTasksDefaultLimit get() = n("find_tasks_default_limit")
    val findTasksMaxLimit get() = n("find_tasks_max_limit")
    val listViewPageSize get() = n("list_view_page_size")
    val getTaskNotesMaxChars get() = n("get_task_notes_max_chars")
    val proposalMaxTasks get() = n("proposal_max_tasks")
    val proposalConfirmTwiceAbove get() = n("proposal_confirm_twice_above")
    val conversationsKept get() = n("conversations_kept")
    val conversationMaxMessages get() = n("conversation_max_messages")
    val requestMaxMessages get() = n("request_max_messages")
    val contextRefreshMinutes get() = n("context_refresh_minutes")
    val brainDumpProjectsMax get() = n("brain_dump_projects_max")
    val brainDumpTasksMax get() = n("brain_dump_tasks_max")
    val brainDumpMaxTokens get() = n("brain_dump_max_tokens")
    val organizeBatchMax get() = n("organize_batch_max")
    val organizePrefetch get() = n("organize_prefetch")
    val organizeNotesMaxChars get() = n("organize_notes_max_chars")
    val organizeReasonMaxChars get() = n("organize_reason_max_chars")
    val organizeMaxTokens get() = n("organize_max_tokens")
    val planDayCandidatesMax get() = n("plan_day_candidates_max")
    val planDayPicksMax get() = n("plan_day_picks_max")
    val planDayMinutesMin get() = n("plan_day_minutes_min")
    val planDayMinutesMax get() = n("plan_day_minutes_max")
    val planDayMaxTokens get() = n("plan_day_max_tokens")
    val savedAddressesMax get() = n("saved_addresses_max")
}

/** A provider preset (section 2.1). */
data class ProviderPreset(
    val id: String,
    val name: String,
    /** `openai-chat` or `anthropic-messages`. */
    val protocol: String,
    val address: String?,
    val addressEditable: Boolean,
    /** `required` or `optional`. */
    val key: String,
    val authHeader: String,
    val authPrefix: String,
    val headers: Map<String, String>,
    /** `json_schema` or `prompt`. */
    val structured: String,
    /** `endpoint`, `in_request` or null. */
    val transcriptionKind: String?,
    val transcriptionPath: String?,
    val suggestedDictation: String?,
    val suggestedAsk: String?,
) {
    val keyRequired: Boolean get() = key == "required"
    val isAnthropic: Boolean get() = protocol == PROTOCOL_ANTHROPIC

    companion object {
        const val PROTOCOL_OPENAI = "openai-chat"
        const val PROTOCOL_ANTHROPIC = "anthropic-messages"

        fun from(o: JsonObject): ProviderPreset {
            val auth = o.obj("auth")
            val transcription = o["transcription"] as? JsonObject
            val suggested = o.obj("suggested")
            return ProviderPreset(
                id = o.str("id"),
                name = o.str("name"),
                protocol = o.str("protocol"),
                address = o.strOrNull("address"),
                addressEditable = o.getValue("addressEditable").jsonPrimitive.booleanOrNull == true,
                key = o.str("key"),
                authHeader = auth.str("header"),
                authPrefix = auth.str("prefix"),
                headers = o.obj("headers").mapValues { it.value.jsonPrimitive.content },
                structured = o.str("structured"),
                transcriptionKind = transcription?.strOrNull("kind"),
                transcriptionPath = transcription?.strOrNull("path"),
                suggestedDictation = suggested.strOrNull("dictation"),
                suggestedAsk = suggested.strOrNull("ask"),
            )
        }
    }
}

internal fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

internal fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

internal fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray

internal fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.let { if (it.isString) null else runCatching { it.long }.getOrNull() }

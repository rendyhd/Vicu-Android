package com.rendyhd.vicu.util.pro

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `parallel` is the default and never written. */
enum class ProjectMode(val key: String) {
    PARALLEL("parallel"),
    SEQUENTIAL("sequential"),
    SINGLE("single"),
}

/**
 * A repeat rule (section 21.13): an end (a day or a number of times) and catching up, beside
 * Vikunja's own repeat. Fields it does not know are kept in [unknown].
 */
data class RepeatRule(
    /** The last local day an occurrence may be due on (`YYYY-MM-DD`). */
    val until: String? = null,
    /** Occurrences left, this one included. */
    val count: Int? = null,
    /** After a completion, move an occurrence still in the past to the first one from today. */
    val catchUp: Boolean = false,
    val unknown: JsonObject = JsonObject(emptyMap()),
) {
    fun toJson(): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>(unknown)
        until?.let { fields["until"] = JsonPrimitive(it) }
        count?.let { fields["count"] = JsonPrimitive(it) }
        if (catchUp) fields["catch_up"] = JsonPrimitive(true)
        return JsonObject(fields)
    }
}

/**
 * Pro's synced preference keys (docs/cross-app-semantics-v1.md sections 11.3 and 21), the same as
 * the desktop's `src/shared/pro/prefs.ts`: project types and statuses, label statuses and kinds,
 * repeat rules. A value that does not have the shape reads as the default and is kept unchanged
 * (section 11.2). Values are the synced preference values by key.
 */
object ProPrefs {
    const val PROJECT_MODE_PREFIX = "project_mode."
    const val PROJECT_STATUS_PREFIX = "project_status."
    const val LABEL_STATUS_PREFIX = "label_status."
    const val LABEL_KIND_PREFIX = "label_kind."
    const val REPEAT_RULE_PREFIX = "repeat_rule."

    /** Every key prefix Pro writes. */
    val PREFIXES: List<String> =
        listOf(PROJECT_MODE_PREFIX, PROJECT_STATUS_PREFIX, LABEL_STATUS_PREFIX, LABEL_KIND_PREFIX, REPEAT_RULE_PREFIX)

    private val WHOLE_NUMBER = Regex("^[1-9]\\d*\$")
    private val LOCAL_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}\$")

    fun projectModeKey(projectId: Long): String = "$PROJECT_MODE_PREFIX$projectId"
    fun projectStatusKey(projectId: Long): String = "$PROJECT_STATUS_PREFIX$projectId"
    fun labelStatusKey(labelId: Long): String = "$LABEL_STATUS_PREFIX$labelId"
    fun labelKindKey(labelId: Long): String = "$LABEL_KIND_PREFIX$labelId"
    fun repeatRuleKey(taskId: Long): String = "$REPEAT_RULE_PREFIX$taskId"

    /** The id at the end of a Pro key, or null when the key is not one of [prefix]'s. */
    fun idOfKey(key: String, prefix: String): Long? {
        if (!key.startsWith(prefix)) return null
        val rest = key.removePrefix(prefix)
        return if (WHOLE_NUMBER.matches(rest)) rest.toLongOrNull() else null
    }

    /** Whether a key is one of Pro's (a whole number above 0 after the prefix). */
    fun isProKey(key: String): Boolean = PREFIXES.any { idOfKey(key, it) != null }

    fun readProjectMode(value: JsonElement?): ProjectMode = when (value.asText()) {
        "sequential" -> ProjectMode.SEQUENTIAL
        "single" -> ProjectMode.SINGLE
        else -> ProjectMode.PARALLEL
    }

    /** A project or a label is on hold only with the value `"on_hold"`. */
    fun readOnHold(value: JsonElement?): Boolean = value.asText() == "on_hold"

    fun readIsPerson(value: JsonElement?): Boolean = value.asText() == "person"

    fun projectMode(values: Map<String, JsonElement?>, projectId: Long): ProjectMode =
        readProjectMode(values[projectModeKey(projectId)])

    fun projectOnHold(values: Map<String, JsonElement?>, projectId: Long): Boolean =
        readOnHold(values[projectStatusKey(projectId)])

    fun labelOnHold(values: Map<String, JsonElement?>, labelId: Long): Boolean =
        readOnHold(values[labelStatusKey(labelId)])

    fun isPersonLabel(values: Map<String, JsonElement?>, labelId: Long): Boolean =
        readIsPerson(values[labelKindKey(labelId)])

    /** The label ids marked as a person. */
    fun personLabelIds(values: Map<String, JsonElement?>): Set<Long> =
        values.mapNotNull { (key, value) -> idOfKey(key, LABEL_KIND_PREFIX)?.takeIf { readIsPerson(value) } }.toSet()

    /** The label ids on hold. */
    fun onHoldLabelIds(values: Map<String, JsonElement?>): Set<Long> =
        values.mapNotNull { (key, value) -> idOfKey(key, LABEL_STATUS_PREFIX)?.takeIf { readOnHold(value) } }.toSet()

    // --- Repeat rules (section 21.13) ------------------------------------------------------

    /** A stored repeat rule, or null when it has none of the three (or is not an object). */
    fun readRepeatRule(value: JsonElement?): RepeatRule? {
        val stored = value as? JsonObject ?: return null
        val unknown = JsonObject(stored.filterKeys { it != "until" && it != "count" && it != "catch_up" })
        val until = stored.text("until")?.takeIf { LOCAL_DATE.matches(it) }
        val count = stored.whole("count")?.takeIf { it >= 1 }?.toInt()
        val catchUp = stored["catch_up"].asFlag() == true
        if (until == null && count == null && !catchUp) return null
        return RepeatRule(until, count, catchUp, unknown)
    }
}

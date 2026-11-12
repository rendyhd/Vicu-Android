package com.rendyhd.vicu.domain.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.rendyhd.vicu.util.pro.ProPrefs
import com.rendyhd.vicu.util.pro.ProSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One synced setting (docs/cross-app-semantics-v1.md, section 11). [value] is any JSON; null means
 * "the default", so a reset reaches every device. The revision is the custom lists' one.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SyncedPrefEntry(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val value: JsonElement? = null,
    val revision: CustomListRevision,
    @SerialName(UNKNOWN_FIELDS_KEY) val unknown: JsonObject = NO_UNKNOWN_FIELDS,
)

/** The preferences carrier's document: one entry per setting a user changed. */
@Serializable
data class SyncedPrefsDocument(
    val version: Int = 1,
    val entries: Map<String, @Serializable(with = SyncedPrefEntrySerializer::class) SyncedPrefEntry> = emptyMap(),
    @SerialName(UNKNOWN_FIELDS_KEY) val unknown: JsonObject = NO_UNKNOWN_FIELDS,
)

internal object SyncedPrefEntrySerializer : UnknownFieldsSerializer<SyncedPrefEntry>(SyncedPrefEntry.serializer())
internal object SyncedPrefsDocumentSerializer : UnknownFieldsSerializer<SyncedPrefsDocument>(SyncedPrefsDocument.serializer())

/** The device's copy of the preferences document, like the custom lists' sync state. */
@Serializable
data class SyncedPrefsLocalState(
    @SerialName("device_id") val deviceId: String,
    val document: SyncedPrefsDocument = SyncedPrefsDocument(),
    val dirty: Boolean = false,
    @SerialName("carrier_task_id") val carrierTaskId: Long? = null,
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
)

/** The keys of the registry (section 11.3). A key is added here in the release that writes it. */
object SyncedPrefKeys {
    const val INBOX_PROJECT_ID = "inbox_project_id"
    const val TASK_ORDER = "task_order"

    /** The local date the last weekly review was finished, `"YYYY-MM-DD"` (section 14.7). */
    const val WEEKLY_REVIEW_LAST = "weekly_review.last"

    /** The length of a new block in minutes (section 16.2). */
    const val PLANNER_DEFAULT_LENGTH = "planner.default_length"

    /** The work hours, `["HH:mm", "HH:mm"]` (section 16.6). */
    const val PLANNER_HOURS = "planner.hours"
    private const val TASK_ORDER_PROJECT_PREFIX = "task_order.project."

    fun taskOrderOf(projectId: Long): String = "$TASK_ORDER_PROJECT_PREFIX$projectId"

    /** The project of a `task_order.project.<id>` key, or null for any other key. */
    fun taskOrderProject(key: String): Long? =
        if (key.startsWith(TASK_ORDER_PROJECT_PREFIX)) key.removePrefix(TASK_ORDER_PROJECT_PREFIX).toLongOrNull() else null

    private const val PROJECT_ICON_PREFIX = "project_icon."

    /** A project's icon (section 11.3): [ICON_NONE], or no entry for the folder. */
    fun projectIconOf(projectId: Long): String = "$PROJECT_ICON_PREFIX$projectId"

    /** The project of a `project_icon.<id>` key, or null for any other key. */
    fun projectIconProject(key: String): Long? =
        if (key.startsWith(PROJECT_ICON_PREFIX)) key.removePrefix(PROJECT_ICON_PREFIX).toLongOrNull() else null

    /** The icon value of a project, and the icon key of a custom list, drawn without an icon. */
    const val ICON_NONE = "none"

    /** The order a person dragged a task's subtasks into (section 19). */
    fun subtaskOrderOf(taskId: Long): String = "subtask_order.$taskId"

    private const val TEMPLATE_PREFIX = "template."

    /** A task template (section 18), or null when it was removed. */
    fun templateOf(id: String): String = "$TEMPLATE_PREFIX$id"

    /** The template of a `template.<id>` key, or null for any other key. */
    fun templateId(key: String): String? =
        key.takeIf { it.startsWith(TEMPLATE_PREFIX) }?.removePrefix(TEMPLATE_PREFIX)?.takeIf { it.isNotEmpty() }

    private const val EVENT_PREFIX = "event."

    /** One of your own Events on the Plan screen (section 20.3), or null when it was deleted. */
    fun eventOf(id: String): String = "$EVENT_PREFIX$id"

    /** The Event of an `event.<id>` key, or null for any other key. */
    fun eventId(key: String): String? =
        key.takeIf { it.startsWith(EVENT_PREFIX) }?.removePrefix(EVENT_PREFIX)?.takeIf { it.isNotEmpty() }

    private const val ROUTINE_REMINDERS_PREFIX = "routine_reminders."
    private const val ROUTINE_HIDDEN_PREFIX = "routine_hidden."

    /** `"off"`: no reminders of that shared routine on this account (section 6.9). */
    fun routineRemindersOf(routineId: String): String = "$ROUTINE_REMINDERS_PREFIX$routineId"

    /** `true`: that shared routine is hidden on this account (section 6.9). */
    fun routineHiddenOf(routineId: String): String = "$ROUTINE_HIDDEN_PREFIX$routineId"

    /** The routine of a `routine_reminders.<id>` key, or null for any other key. */
    fun routineRemindersRoutine(key: String): String? =
        key.takeIf { it.startsWith(ROUTINE_REMINDERS_PREFIX) }?.removePrefix(ROUTINE_REMINDERS_PREFIX)?.takeIf { it.isNotEmpty() }

    /** The routine of a `routine_hidden.<id>` key, or null for any other key. */
    fun routineHiddenRoutine(key: String): String? =
        key.takeIf { it.startsWith(ROUTINE_HIDDEN_PREFIX) }?.removePrefix(ROUTINE_HIDDEN_PREFIX)?.takeIf { it.isNotEmpty() }

    // Pro (sections 11.3 and 21): written only while Pro is on, kept by every version.

    /** The project categories promises and the portfolio apply in (section 21.1). */
    const val PRO_AREA = ProSettings.AREA_KEY

    /** `"sequential"` or `"single"`; parallel has no entry. */
    fun projectModeOf(projectId: Long): String = ProPrefs.projectModeKey(projectId)

    /** `"on_hold"`, or no entry. */
    fun projectStatusOf(projectId: Long): String = ProPrefs.projectStatusKey(projectId)

    /** `"on_hold"`, or no entry. */
    fun labelStatusOf(labelId: Long): String = ProPrefs.labelStatusKey(labelId)

    /** `"person"`, or no entry. */
    fun labelKindOf(labelId: Long): String = ProPrefs.labelKindKey(labelId)

    /** `{ "until"?, "count"?, "catch_up"? }` beside the task's repeat (section 21.13). */
    fun repeatRuleOf(taskId: Long): String = ProPrefs.repeatRuleKey(taskId)

    /** True for one of Pro's keys: the area, or a Pro prefix and an id. */
    fun isProKey(key: String): Boolean = key == PRO_AREA || ProPrefs.isProKey(key)

    /**
     * Whether Pro may write [value] to [key] (the desktop's `isWritablePref` for the Pro keys):
     * null resets any of them; otherwise only the values the registry allows.
     */
    fun isWritableProValue(key: String, value: JsonElement?): Boolean {
        if (!isProKey(key)) return false
        if (value == null || value is JsonNull) return true
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when {
            key == PRO_AREA -> value is JsonArray && ProSettings.readArea(value).size == value.size
            ProPrefs.idOfKey(key, ProPrefs.PROJECT_MODE_PREFIX) != null -> text == "sequential" || text == "single"
            ProPrefs.idOfKey(key, ProPrefs.PROJECT_STATUS_PREFIX) != null ||
                ProPrefs.idOfKey(key, ProPrefs.LABEL_STATUS_PREFIX) != null -> text == "on_hold"
            ProPrefs.idOfKey(key, ProPrefs.LABEL_KIND_PREFIX) != null -> text == "person"
            ProPrefs.idOfKey(key, ProPrefs.REPEAT_RULE_PREFIX) != null -> ProPrefs.readRepeatRule(value) != null
            else -> false
        }
    }
}

/**
 * This account's own choices about the routines others shared with it (section 6.9): never part of
 * a routine, so they reach every device of the account and nobody else.
 */
data class RoutinePersonalSettings(
    /** Routines whose reminders are off on this account. */
    val remindersOff: Set<String> = emptySet(),
    /** Routines hidden on this account. */
    val hidden: Set<String> = emptySet(),
) {
    companion object {
        /** Reads the entries; any other value than `"off"` and `true` acts as the default (section 11.3). */
        fun fromEntries(entries: Map<String, JsonElement?>): RoutinePersonalSettings {
            val off = mutableSetOf<String>()
            val hidden = mutableSetOf<String>()
            for ((key, value) in entries) {
                val primitive = value as? JsonPrimitive ?: continue
                SyncedPrefKeys.routineRemindersRoutine(key)?.let { if (primitive.isString && primitive.content == "off") off += it }
                SyncedPrefKeys.routineHiddenRoutine(key)?.let { if (!primitive.isString && primitive.content == "true") hidden += it }
            }
            return RoutinePersonalSettings(off, hidden)
        }
    }
}

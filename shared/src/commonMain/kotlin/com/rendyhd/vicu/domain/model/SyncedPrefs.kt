package com.rendyhd.vicu.domain.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
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

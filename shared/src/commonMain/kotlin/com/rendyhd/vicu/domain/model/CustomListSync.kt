package com.rendyhd.vicu.domain.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * Where a list value or filter keeps the fields this version does not know, between decoding and
 * encoding. It never appears on the wire: [UnknownFieldsSerializer] moves the fields in and out.
 */
internal const val UNKNOWN_FIELDS_KEY = "__unknown__"

internal val NO_UNKNOWN_FIELDS = JsonObject(emptyMap())

/**
 * A synced list filter. Fields a newer app added are kept in [unknown] and written back, so
 * editing a list on this device does not erase them (docs/cross-app-semantics-v1.md, section 3).
 * Encode and decode it through [CustomListWireFilterSerializer]; [CustomListWire.filter] does.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CustomListWireFilter(
    @SerialName("project_ids") val projectIds: List<Long> = emptyList(),
    @SerialName("project_filter_mode") val projectFilterMode: String = "include",
    @SerialName("add_to_project_id") val addToProjectId: Long = 0L,
    @SerialName("sort_by") val sortBy: String = "due_date",
    @SerialName("order_by") val orderBy: String = "asc",
    @SerialName("due_date_filter") val dueDateFilter: String = "all",
    @SerialName("priority_filter") val priorityFilter: List<Int> = emptyList(),
    @SerialName("label_ids") val labelIds: List<Long> = emptyList(),
    @SerialName("include_done") val includeDone: Boolean = false,
    @SerialName("include_today_all_projects") val includeTodayAllProjects: Boolean = false,
    /** Absent means true. Never written while absent, whatever the JSON configuration. */
    @SerialName("include_overdue") @EncodeDefault(EncodeDefault.Mode.NEVER) val includeOverdue: Boolean? = null,
    // The collaboration and activity conditions (section 3.3), written only when set.
    @SerialName("assigned") @EncodeDefault(EncodeDefault.Mode.NEVER) val assigned: String? = null,
    @SerialName("comments") @EncodeDefault(EncodeDefault.Mode.NEVER) val comments: String? = null,
    @SerialName("shared_only") @EncodeDefault(EncodeDefault.Mode.NEVER) val sharedOnly: Boolean? = null,
    @SerialName("changed_within_days") @EncodeDefault(EncodeDefault.Mode.NEVER) val changedWithinDays: Int? = null,
    // The rule and the further sort keys (sections 3.5 and 3.6), written only when set.
    @SerialName("rule") @EncodeDefault(EncodeDefault.Mode.NEVER) val rule: String? = null,
    @SerialName("rule_include_nulls") @EncodeDefault(EncodeDefault.Mode.NEVER) val ruleIncludeNulls: Boolean? = null,
    @SerialName("then_by") @EncodeDefault(EncodeDefault.Mode.NEVER) val thenBy: List<CustomListWireSortKey>? = null,
    @SerialName(UNKNOWN_FIELDS_KEY) val unknown: JsonObject = NO_UNKNOWN_FIELDS,
)

/** A `then_by` entry (section 3.6). */
@Serializable
data class CustomListWireSortKey(
    @SerialName("sort_by") val sortBy: String,
    @SerialName("order_by") val orderBy: String = "asc",
)

/**
 * A synced list value. Like [CustomListWireFilter] it keeps the fields it does not know in
 * [unknown]; encode and decode it through [CustomListWireSerializer]
 * ([CustomListSyncRecord.value] does).
 */
@Serializable
data class CustomListWire(
    val id: String,
    val name: String,
    val icon: String = "",
    @Serializable(with = CustomListWireFilterSerializer::class) val filter: CustomListWireFilter,
    /** The saved filter the list was imported from (section 3.7); written only when set. */
    @OptIn(ExperimentalSerializationApi::class)
    @SerialName("saved_filter_id") @EncodeDefault(EncodeDefault.Mode.NEVER) val savedFilterId: Long? = null,
    @SerialName(UNKNOWN_FIELDS_KEY) val unknown: JsonObject = NO_UNKNOWN_FIELDS,
)

/**
 * Moves the JSON keys the typed class does not declare into its [UNKNOWN_FIELDS_KEY] property when
 * decoding, and flattens that property back into the object when encoding, so the extra fields
 * stay where the other app put them. A key the typed class declares always wins over a copy of it
 * among the unknown fields.
 */
internal open class UnknownFieldsSerializer<T>(delegate: KSerializer<T>) : JsonTransformingSerializer<T>(delegate) {
    private val declared: Set<String> =
        (0 until delegate.descriptor.elementsCount).mapTo(HashSet()) { delegate.descriptor.getElementName(it) } -
            UNKNOWN_FIELDS_KEY

    override fun transformDeserialize(element: JsonElement): JsonElement {
        // A value that is not an object fails in the delegate with its usual message.
        val fields = element as? JsonObject ?: return element
        val extra = fields.filterKeys { it !in declared }
        if (extra.isEmpty()) return fields
        return JsonObject(fields.filterKeys { it in declared } + (UNKNOWN_FIELDS_KEY to JsonObject(extra)))
    }

    override fun transformSerialize(element: JsonElement): JsonElement {
        val fields = element as? JsonObject ?: return element
        val extra = fields[UNKNOWN_FIELDS_KEY] as? JsonObject ?: return fields.withoutUnknownKey()
        return JsonObject(extra.filterKeys { it !in declared } + fields.withoutUnknownKey())
    }

    private fun JsonObject.withoutUnknownKey(): JsonObject =
        if (UNKNOWN_FIELDS_KEY in this) JsonObject(filterKeys { it != UNKNOWN_FIELDS_KEY }) else this
}

internal object CustomListWireFilterSerializer :
    UnknownFieldsSerializer<CustomListWireFilter>(CustomListWireFilter.serializer())

internal object CustomListWireSerializer : UnknownFieldsSerializer<CustomListWire>(CustomListWire.serializer())

@Serializable
data class CustomListRevision(
    @SerialName("wall_time_ms") val wallTimeMs: Long,
    val counter: Long,
    @SerialName("device_id") val deviceId: String,
)

@Serializable
data class CustomListSyncRecord(
    @Serializable(with = CustomListWireSerializer::class) val value: CustomListWire? = null,
    val revision: CustomListRevision,
    @SerialName(UNKNOWN_FIELDS_KEY) val unknown: JsonObject = NO_UNKNOWN_FIELDS,
)

@Serializable
data class CustomListSyncOrder(
    val ids: List<String> = emptyList(),
    val revision: CustomListRevision,
    @SerialName(UNKNOWN_FIELDS_KEY) val unknown: JsonObject = NO_UNKNOWN_FIELDS,
)

/**
 * The synced custom lists. Like the lists themselves, the document, its records and the list order
 * keep the fields a newer app added (docs/cross-app-semantics-v1.md, section 10.8); encode and
 * decode it for the carrier through [CustomListSyncDocumentSerializer].
 */
@Serializable
data class CustomListSyncDocumentV1(
    val version: Int = 1,
    val lists: Map<String, @Serializable(with = CustomListSyncRecordSerializer::class) CustomListSyncRecord> = emptyMap(),
    @Serializable(with = CustomListSyncOrderSerializer::class) val order: CustomListSyncOrder,
    /** The saved filters any device imported (section 3.7), merged as a union; written only when set. */
    @OptIn(ExperimentalSerializationApi::class)
    @SerialName("imported_saved_filters") @EncodeDefault(EncodeDefault.Mode.NEVER) val importedSavedFilters: List<Long>? = null,
    @SerialName(UNKNOWN_FIELDS_KEY) val unknown: JsonObject = NO_UNKNOWN_FIELDS,
)

internal object CustomListSyncRecordSerializer : UnknownFieldsSerializer<CustomListSyncRecord>(CustomListSyncRecord.serializer())
internal object CustomListSyncOrderSerializer : UnknownFieldsSerializer<CustomListSyncOrder>(CustomListSyncOrder.serializer())
internal object CustomListSyncDocumentSerializer : UnknownFieldsSerializer<CustomListSyncDocumentV1>(CustomListSyncDocumentV1.serializer())

@Serializable
data class CustomListSyncLocalState(
    @SerialName("device_id") val deviceId: String,
    val document: CustomListSyncDocumentV1,
    val dirty: Boolean = false,
    @SerialName("carrier_task_id") val carrierTaskId: Long? = null,
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
)

sealed interface CustomListSyncStatus {
    data object Idle : CustomListSyncStatus
    data object Syncing : CustomListSyncStatus
    data object Pending : CustomListSyncStatus
    data class Offline(val message: String) : CustomListSyncStatus
    data class Error(val message: String) : CustomListSyncStatus
    data class UpdateRequired(val message: String = "Update Vicu to sync custom lists") : CustomListSyncStatus
}

/**
 * The wire form of this list. [preserving] is the value it replaces: the fields of it that this
 * version does not know are carried over, so an edit here does not drop what another app wrote.
 */
fun CustomList.toWire(preserving: CustomListWire? = null): CustomListWire = CustomListWire(
    id = id,
    name = name.trim(),
    icon = icon,
    filter = CustomListWireFilter(
        projectIds = filter.projectIds.distinct(),
        projectFilterMode = if (filter.projectFilterMode == "exclude") "exclude" else "include",
        addToProjectId = filter.addToProjectId,
        sortBy = filter.sortBy,
        orderBy = if (filter.orderBy == "desc") "desc" else "asc",
        dueDateFilter = filter.dueDateFilter,
        priorityFilter = filter.priorityFilter.distinct(),
        labelIds = filter.labelIds.distinct(),
        includeDone = filter.includeDone,
        includeTodayAllProjects = filter.includeTodayAllProjects,
        // Absent unless the user turned it off, like desktop (which removes the key when on).
        includeOverdue = if (filter.includeOverdue == false) false else null,
        // Like desktop: a non-empty string is kept (a value from a newer app too), shared_only only
        // when true, changed_within_days only as 1 or more.
        assigned = filter.assigned?.takeIf { it.isNotEmpty() },
        comments = filter.comments?.takeIf { it.isNotEmpty() },
        sharedOnly = if (filter.sharedOnly) true else null,
        changedWithinDays = filter.changedDays,
        rule = filter.ruleText,
        ruleIncludeNulls = if (filter.ruleIncludeNulls) true else null,
        thenBy = filter.thenBy.takeIf { it.isNotEmpty() }?.map {
            CustomListWireSortKey(it.sortBy, if (it.orderBy == "desc") "desc" else "asc")
        },
        unknown = preserving?.filter?.unknown ?: NO_UNKNOWN_FIELDS,
    ),
    savedFilterId = savedFilterId,
    unknown = preserving?.unknown ?: NO_UNKNOWN_FIELDS,
)

fun CustomListWire.toDomain(): CustomList = CustomList(
    id = id,
    name = name.trim(),
    icon = icon,
    filter = CustomListFilter(
        projectIds = filter.projectIds.distinct(),
        projectFilterMode = if (filter.projectFilterMode == "exclude") "exclude" else "include",
        addToProjectId = filter.addToProjectId,
        sortBy = filter.sortBy,
        orderBy = if (filter.orderBy == "desc") "desc" else "asc",
        dueDateFilter = filter.dueDateFilter,
        priorityFilter = filter.priorityFilter.distinct(),
        labelIds = filter.labelIds.distinct(),
        includeDone = filter.includeDone,
        includeTodayAllProjects = filter.includeTodayAllProjects,
        includeOverdue = filter.includeOverdue,
        assigned = filter.assigned?.takeIf { it.isNotEmpty() },
        comments = filter.comments?.takeIf { it.isNotEmpty() },
        sharedOnly = filter.sharedOnly == true,
        changedWithinDays = filter.changedWithinDays,
        rule = filter.rule?.takeIf { it.isNotBlank() },
        ruleIncludeNulls = filter.ruleIncludeNulls == true,
        thenBy = filter.thenBy.orEmpty().map { ListSortKey(it.sortBy, if (it.orderBy == "desc") "desc" else "asc") },
    ),
    savedFilterId = savedFilterId,
)

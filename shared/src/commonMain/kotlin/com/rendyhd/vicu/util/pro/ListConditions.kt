package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Task
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class GroupBy(val key: String) {
    PROJECT("project"),
    LABEL("label"),
    PERSON("person"),
    DUE("due"),
    START("start"),
    FLAGGED("flagged"),
    NONE("none"),
}

enum class RowField(val key: String) {
    PROJECT("project"),
    LABELS("labels"),
    DUE("due"),
    START("start"),
    ESTIMATE("estimate"),
    FLAG("flag"),
    NOTES("notes"),
}

/**
 * The Pro keys of a custom list's filter (docs/cross-app-semantics-v1.md section 3.8), read the way
 * the desktop reads them: a value of the wrong shape is no condition, and the wire object keeps it
 * as it is. [availability], [projectStatus], [groupBy] and [promise] are kept as written (a value
 * from a newer app is no condition here); [rowFields] is the list as written.
 */
@Serializable
data class ProListFilter(
    val availability: String? = null,
    val flagged: Boolean? = null,
    @SerialName("estimate_max") val estimateMax: Double? = null,
    @SerialName("project_status") val projectStatus: String? = null,
    @SerialName("group_by") val groupBy: String? = null,
    @SerialName("row_fields") val rowFields: List<String>? = null,
    val promise: String? = null,
    val person: List<Long> = emptyList(),
) {
    /** The estimate limit when it is a whole number of 1 or more, else null. */
    val estimateLimit: Int?
        get() = estimateMax?.takeIf { it >= 1 && it == kotlin.math.floor(it) }?.toInt()

    /** Whether any Pro condition is set (the editor shows the Pro section for such a list on any device). */
    val hasCondition: Boolean
        get() = AvailabilityView.read(availability) != null ||
            flagged != null ||
            estimateLimit != null ||
            projectStatus == "active" || projectStatus == "on_hold" ||
            promise == "theirs" || promise == "mine" || promise == "any" ||
            person.isNotEmpty()

    /** The Pro keys that are set, as they are written to the wire object. */
    fun toJson(): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        availability?.let { fields["availability"] = JsonPrimitive(it) }
        flagged?.let { fields["flagged"] = JsonPrimitive(it) }
        estimateMax?.let { fields["estimate_max"] = JsonPrimitive(if (it == kotlin.math.floor(it)) it.toLong() else it) }
        projectStatus?.let { fields["project_status"] = JsonPrimitive(it) }
        groupBy?.let { fields["group_by"] = JsonPrimitive(it) }
        rowFields?.let { fields["row_fields"] = JsonArray(it.map(::JsonPrimitive)) }
        promise?.let { fields["promise"] = JsonPrimitive(it) }
        if (person.isNotEmpty()) fields["person"] = JsonArray(person.map(::JsonPrimitive))
        return JsonObject(fields)
    }

    companion object {
        val NONE = ProListFilter()

        /** The wire keys of the Pro conditions and display choices. */
        val KEYS: Set<String> = setOf(
            "availability", "flagged", "estimate_max", "project_status", "group_by", "row_fields", "promise", "person",
        )

        /** Reads the Pro keys of a filter object; anything that does not have the shape is left out. */
        fun read(filter: JsonObject): ProListFilter = ProListFilter(
            availability = filter.text("availability"),
            flagged = filter["flagged"].asFlag(),
            estimateMax = filter.number("estimate_max"),
            projectStatus = filter.text("project_status"),
            groupBy = filter.text("group_by"),
            rowFields = filter.list("row_fields")?.mapNotNull { it.asText() },
            promise = filter.text("promise"),
            person = filter.wholes("person"),
        )
    }
}

/** What the Pro conditions read; without it (no data yet) they are no condition. */
data class ProListContext(
    val availability: AvailabilityContext? = null,
    val ledger: ProLedger? = null,
    val projectOnHold: ((Long) -> Boolean)? = null,
)

/**
 * Pro's custom-list conditions (docs/cross-app-semantics-v1.md section 3.8 and 21), the same as the
 * desktop's `src/shared/pro/list-conditions.ts`: availability, flagged, estimate, project status,
 * promise and person, plus how the list groups and what rows show. Evaluated by every version that
 * knows them, Pro on or off (decision 23); a version that does not know them keeps them and shows
 * more. Only on the device: the server filter stays a superset.
 */
object ListConditions {
    val GROUP_BY_VALUES: List<GroupBy> = GroupBy.entries
    val ROW_FIELDS: List<RowField> = RowField.entries

    fun readGroupBy(value: JsonElement?): GroupBy = GroupBy.entries.firstOrNull { it.key == value.asText() } ?: GroupBy.NONE

    /** The row fields in a list's order, unknown ones left out; null when the list does not say. */
    fun readRowFields(value: JsonElement?): List<RowField>? {
        val array = value as? JsonArray ?: return null
        return array.mapNotNull { entry -> RowField.entries.firstOrNull { it.key == entry.asText() } }
    }

    /**
     * The Pro conditions for one task. `availability`: `available` keeps open tasks without a
     * reason, `remaining` open tasks, `all` (or another value) no condition. `flagged`: the favourite
     * equals it. `estimate_max`: an estimate of at most that many minutes (no estimate: no match).
     * `project_status`: `active` not on hold, `on_hold` on hold. `promise`: an open promise of that
     * direction (`any`: either). `person`: the promise's person or one of the task's labels is in the
     * list.
     */
    fun matches(task: Task, filter: ProListFilter, context: ProListContext?): Boolean {
        val view = AvailabilityView.read(filter.availability)
        if (view == AvailabilityView.REMAINING && task.done) return false
        if (view == AvailabilityView.AVAILABLE) {
            if (task.done) return false
            val availability = context?.availability
            if (availability != null && !availability.of(task).available) return false
        }
        if (filter.flagged != null && task.isFavorite != filter.flagged) return false
        val estimateMax = filter.estimateLimit
        val ledger = context?.ledger
        if (estimateMax != null && ledger != null) {
            val minutes = ledger.storedMinutes(task.id)
            if (minutes == null || minutes > estimateMax) return false
        }
        val onHold = context?.projectOnHold
        if ((filter.projectStatus == "active" || filter.projectStatus == "on_hold") && onHold != null) {
            if (onHold(task.projectId) != (filter.projectStatus == "on_hold")) return false
        }
        val promiseWanted = filter.promise == "theirs" || filter.promise == "mine" || filter.promise == "any"
        val people = filter.person
        if ((promiseWanted || people.isNotEmpty()) && ledger != null) {
            val promise = ledger.value("promises", task.id)
            val open = promise?.takeIf { LedgerRules.finalVerdictOf(it) == null }
            if (promiseWanted && (open == null || (filter.promise != "any" && open.text("dir") != filter.promise))) return false
            if (people.isNotEmpty()) {
                val owner = open?.whole("person")
                val byPromise = owner != null && owner in people
                val byLabel = task.labels.any { it.id in people }
                if (!byPromise && !byLabel) return false
            }
        }
        return true
    }
}

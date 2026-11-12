package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.domain.model.SyncedPrefKeys
import com.rendyhd.vicu.util.pro.ProPrefs
import com.rendyhd.vicu.util.pro.ProSettings
import com.rendyhd.vicu.util.pro.ProjectMode
import com.rendyhd.vicu.util.pro.RepeatRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Pro's synced keys as one device read them, with the typed reads of `util/pro/ProPrefs`. */
data class ProPrefsSnapshot(val values: Map<String, JsonElement?> = emptyMap()) {
    /** The project categories of the area; empty means every project. */
    val area: List<String> get() = ProSettings.readArea(values[SyncedPrefKeys.PRO_AREA])

    /** The labels marked as a person. */
    val people: Set<Long> get() = ProPrefs.personLabelIds(values)

    /** The labels on hold. */
    val heldLabels: Set<Long> get() = ProPrefs.onHoldLabelIds(values)

    fun isPerson(labelId: Long): Boolean = ProPrefs.isPersonLabel(values, labelId)
    fun labelOnHold(labelId: Long): Boolean = ProPrefs.labelOnHold(values, labelId)
    fun projectMode(projectId: Long): ProjectMode = ProPrefs.projectMode(values, projectId)
    fun projectOnHold(projectId: Long): Boolean = ProPrefs.projectOnHold(values, projectId)
    fun repeatRule(taskId: Long): RepeatRule? = ProPrefs.readRepeatRule(values[SyncedPrefKeys.repeatRuleOf(taskId)])
}

/**
 * Pro's synced keys (docs/cross-app-semantics-v1.md sections 11.3 and 21), the desktop's
 * `use-pro-prefs.ts`: project types and statuses, label statuses and kinds, repeat rules and the
 * area, read from the synced preferences and written through them (one change, one sync). The
 * default of each is no entry, so going back to it writes null and the reset reaches every device.
 * Only the Pro screens call the setters, and those only show while Pro is on.
 */
class ProPreferences(private val synced: SyncedEntries) {

    val snapshot: Flow<ProPrefsSnapshot> = synced.entries
        .map { entries -> ProPrefsSnapshot(entries.filterKeys(SyncedPrefKeys::isProKey)) }
        .distinctUntilChanged()

    suspend fun setProjectMode(projectId: Long, mode: ProjectMode): Boolean =
        write(SyncedPrefKeys.projectModeOf(projectId), mode.takeUnless { it == ProjectMode.PARALLEL }?.let { JsonPrimitive(it.key) })

    suspend fun setProjectOnHold(projectId: Long, held: Boolean): Boolean =
        write(SyncedPrefKeys.projectStatusOf(projectId), if (held) JsonPrimitive(ON_HOLD) else null)

    suspend fun setLabelOnHold(labelId: Long, held: Boolean): Boolean =
        write(SyncedPrefKeys.labelStatusOf(labelId), if (held) JsonPrimitive(ON_HOLD) else null)

    suspend fun setPerson(labelId: Long, person: Boolean): Boolean =
        write(SyncedPrefKeys.labelKindOf(labelId), if (person) JsonPrimitive(PERSON) else null)

    /** A rule with none of its three parts is no rule: null. */
    suspend fun setRepeatRule(taskId: Long, rule: RepeatRule?): Boolean =
        write(SyncedPrefKeys.repeatRuleOf(taskId), rule?.toJson()?.takeIf { ProPrefs.readRepeatRule(it) != null })

    /** The area's categories; none is every project and writes null. */
    suspend fun setArea(categoryIds: List<String>): Boolean =
        write(SyncedPrefKeys.PRO_AREA, categoryIds.distinct().takeIf { it.isNotEmpty() }?.let { ids -> JsonArray(ids.map(::JsonPrimitive)) })

    /** Writes one key; false (and nothing written) for a value the registry does not allow. */
    private suspend fun write(key: String, value: JsonElement?): Boolean {
        if (!SyncedPrefKeys.isWritableProValue(key, value)) return false
        synced.set(key, value)
        return true
    }

    private companion object {
        const val ON_HOLD = "on_hold"
        const val PERSON = "person"
    }
}

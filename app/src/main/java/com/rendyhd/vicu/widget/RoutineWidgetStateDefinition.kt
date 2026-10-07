package com.rendyhd.vicu.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.state.GlanceStateDefinition
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.RoutineDay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class RoutineWidgetItem(
    val key: String,
    val routineId: String,
    val routineName: String,
    val scheduledDate: String,
    val slotId: String,
    val slotLabel: String,
    val status: OccurrenceStatus,
)

@Serializable
data class RoutineWidgetState(
    val date: String = "",
    val occurrences: List<RoutineWidgetItem> = emptyList(),
) {
    val scheduledCount: Int get() = occurrences.size
    val completedCount: Int get() = occurrences.count { it.status == OccurrenceStatus.COMPLETED }
    val visibleOccurrences: List<RoutineWidgetItem>
        get() = occurrences.filterNot { it.status == OccurrenceStatus.COMPLETED }
    val isAllDone: Boolean get() = occurrences.isNotEmpty() && visibleOccurrences.isEmpty()

    fun withStatus(
        routineId: String,
        date: String,
        slotId: String,
        status: OccurrenceStatus,
    ): RoutineWidgetState = copy(
        occurrences = occurrences.map { occurrence ->
            if (
                occurrence.routineId == routineId &&
                occurrence.scheduledDate == date &&
                occurrence.slotId == slotId
            ) {
                occurrence.copy(status = status)
            } else {
                occurrence
            }
        },
    )

    companion object {
        fun from(day: RoutineDay): RoutineWidgetState = RoutineWidgetState(
            date = day.date,
            occurrences = day.occurrences.map { occurrence ->
                RoutineWidgetItem(
                    key = occurrence.key,
                    routineId = occurrence.routine.definition.id,
                    routineName = occurrence.routine.definition.name,
                    scheduledDate = occurrence.scheduledDate,
                    slotId = occurrence.slot.id,
                    slotLabel = occurrence.slot.label,
                    status = occurrence.status,
                )
            },
        )
    }
}

object RoutineWidgetStateDefinition : GlanceStateDefinition<Preferences> {
    val KEY_STATE = stringPreferencesKey("routine_widget_state")

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getDataStore(
        context: Context,
        fileKey: String,
    ) = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
        WidgetStateFiles.locate(context, "routine_widget_state_$fileKey")
    }

    override fun getLocation(context: Context, fileKey: String): File =
        WidgetStateFiles.locate(context, "routine_widget_state_$fileKey")

    fun parseState(prefs: Preferences): RoutineWidgetState {
        val raw = prefs[KEY_STATE] ?: return RoutineWidgetState()
        return runCatching {
            json.decodeFromString(RoutineWidgetState.serializer(), raw)
        }.getOrDefault(RoutineWidgetState())
    }

    fun encodeState(state: RoutineWidgetState): String =
        json.encodeToString(RoutineWidgetState.serializer(), state)
}

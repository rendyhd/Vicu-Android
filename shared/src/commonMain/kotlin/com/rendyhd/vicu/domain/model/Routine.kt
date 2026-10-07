package com.rendyhd.vicu.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RoutineKind {
    HEALTH,
    CHORE,
}

@Serializable
enum class HealthSubtype {
    SUPPLEMENT,
    MEDICATION,
}

@Serializable
enum class RoutinePeriod {
    MORNING,
    AFTERNOON,
    EVENING,
    ANYTIME,
    HOME,
}

@Serializable
enum class OccurrenceStatus {
    PENDING,
    COMPLETED,
    SKIPPED,
    NOT_LOGGED,
}

@Serializable
sealed class RoutineSchedule {
    @Serializable
    @SerialName("calendar")
    data class Calendar(
        /** ISO-8601 weekdays, Monday=1 through Sunday=7. Empty means every day. */
        val weekdays: Set<Int> = emptySet(),
        val weekInterval: Int = 1,
        val anchorDate: String,
    ) : RoutineSchedule()

    @Serializable
    @SerialName("after_completion")
    data class AfterCompletion(
        val intervalDays: Int,
        val firstDueDate: String,
    ) : RoutineSchedule()
}

@Serializable
data class RoutineSlot(
    val id: String,
    val label: String,
    val period: RoutinePeriod,
    /** Minutes after local midnight. */
    val reminderMinutes: Int = 8 * 60,
    val reminderEnabled: Boolean = true,
    /** Zero disables the follow-up. */
    val followUpMinutes: Int = 30,
)

@Serializable
data class RoutineDefinition(
    val id: String,
    val name: String,
    val kind: RoutineKind,
    val healthSubtype: HealthSubtype? = null,
    val amount: String = "",
    val unit: String = "",
    val iconName: String = "",
    val color: String = "",
    val schedule: RoutineSchedule,
    val slots: List<RoutineSlot>,
    val activeFrom: String,
    val archived: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
    val updatedBy: String,
)

@Serializable
data class RoutineOccurrenceRecord(
    val key: String,
    val routineId: String,
    val slotId: String,
    val scheduledDate: String,
    val scheduledMinutes: Int,
    val timeZoneId: String,
    val status: OccurrenceStatus,
    val loggedAt: String = "",
    val modifiedAt: String,
    val modifiedBy: String,
    val note: String = "",
)

@Serializable
data class RoutinePayload(
    val version: Int = 1,
    val definition: RoutineDefinition,
    val occurrences: Map<String, RoutineOccurrenceRecord> = emptyMap(),
    val prunedBefore: String = "",
)

/**
 * One archive part: older occurrences of one routine, held by a hidden done task on the server
 * (docs/cross-app-semantics-v1.md, section 6.4). A routine can have any number of parts; reading
 * its history merges the main carrier with every part by key.
 */
@Serializable
data class RoutineArchivePart(
    val version: Int = 1,
    val routineId: String,
    /** 1-based; duplicates of a number are allowed and merge like any other part. */
    val part: Int,
    val occurrences: Map<String, RoutineOccurrenceRecord> = emptyMap(),
)

data class Routine(
    val taskId: Long,
    val payload: RoutinePayload,
) {
    val definition: RoutineDefinition get() = payload.definition
}

data class RoutineOccurrence(
    val routine: Routine,
    val key: String,
    val slot: RoutineSlot,
    val scheduledDate: String,
    val status: OccurrenceStatus,
    val loggedAt: String = "",
    val note: String = "",
    val overdue: Boolean = false,
)

data class RoutineDay(
    val date: String,
    val occurrences: List<RoutineOccurrence>,
) {
    val scheduledCount: Int get() = occurrences.size
    val completedCount: Int get() = occurrences.count { it.status == OccurrenceStatus.COMPLETED }
}

data class RoutineDraft(
    val name: String,
    val kind: RoutineKind,
    val healthSubtype: HealthSubtype? = null,
    val amount: String = "",
    val unit: String = "",
    val iconName: String = "",
    val color: String = "",
    val schedule: RoutineSchedule,
    val slots: List<RoutineSlot>,
)

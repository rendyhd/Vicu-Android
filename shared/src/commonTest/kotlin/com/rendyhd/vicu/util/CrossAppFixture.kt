package com.rendyhd.vicu.util

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The shared contract vectors (`test-fixtures/cross-app-semantics-v1.json`, identical in the
 * desktop repo). Times in the file are local wall-clock times without an offset; [local] turns
 * them into instants in an explicit zone and [wall] turns an instant back into that notation.
 */
object CrossAppFixture {

    @Serializable
    data class Display(val local: String, val dateOnly: Boolean)

    @Serializable
    data class Setter(
        val reference: String,
        val action: String,
        val date: String? = null,
        val days: Int? = null,
        val from: String? = null,
        val expect: String,
    )

    @Serializable
    data class DueDatesSection(val display: List<Display>, val setters: List<Setter>)

    @Serializable
    data class Week(val today: String, val thisWeekEnd: String, val thisMonthEnd: String, val nextWeekStart: String)

    @Serializable
    data class FixtureTask(
        val id: Long,
        val projectId: Long,
        val due: String? = null,
        val done: Boolean,
        val priority: Int,
        val labelIds: List<Long>,
    )

    @Serializable
    data class SmartList(
        val today: String,
        val todayOverdue: List<Long>,
        val todayToday: List<Long>,
        val upcoming: List<Long>,
    )

    @Serializable
    data class ReviewMeta(
        val state: String,
        val lastReviewedAt: String? = null,
        val cadenceDaysOverride: Int? = null,
    )

    @Serializable
    data class ReviewExpect(
        val isOverdue: Boolean,
        val daysSince: Long? = null,
        val daysUntil: Long? = null,
        val next: String? = null,
    )

    @Serializable
    data class Review(
        val name: String,
        val today: String,
        val defaultCadence: Int,
        val meta: ReviewMeta,
        val expect: ReviewExpect,
    )

    @Serializable
    data class Fixture(
        val contractVersion: Int,
        val dueDates: DueDatesSection,
        val weeks: List<Week>,
        val tasks: List<FixtureTask>,
        val smartLists: List<SmartList>,
        val review: List<Review>,
    )

    /** The zones every vector runs in: ahead of UTC, behind UTC, and far ahead (UTC+12/13). */
    val zones: List<TimeZone> = listOf(
        TimeZone.of("Europe/Amsterdam"),
        TimeZone.of("America/New_York"),
        TimeZone.of("Pacific/Auckland"),
    )

    private val json = Json { ignoreUnknownKeys = true }

    val fixture: Fixture by lazy { json.decodeFromString(Fixture.serializer(), readFixtureText()) }

    private fun readFixtureText(): String {
        // Gradle runs the tests with the module directory as the working directory; search
        // upward so running them from the repo root or an IDE works too.
        val start: String = System.getProperty("user.dir") ?: "."
        var dir: File? = File(start).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "test-fixtures/cross-app-semantics-v1.json")
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            dir = dir.parentFile
        }
        error("test-fixtures/cross-app-semantics-v1.json not found above $start")
    }

    /** `2026-10-06T10:00:00` (or a bare `2026-10-06`) as an instant in [zone]. */
    fun local(value: String, zone: TimeZone): Instant {
        val withTime = if ('T' in value) value else "${value}T00:00:00"
        return LocalDateTime.parse(withTime).toInstant(zone)
    }

    /** An instant back to `YYYY-MM-DDTHH:mm:ss` local wall-clock time in [zone]. */
    fun wall(instant: Instant, zone: TimeZone): String {
        val t = instant.toLocalDateTime(zone)
        fun p(n: Int) = n.toString().padStart(2, '0')
        return "${t.year}-${p(t.monthNumber)}-${p(t.dayOfMonth)}T${p(t.hour)}:${p(t.minute)}:${p(t.second)}"
    }

    fun date(value: String): LocalDate = LocalDate.parse(value)
}

/** A time source frozen at one instant in one zone. */
class FixedTimeSource(private val now: Instant, private val zone: TimeZone) : TimeSource {
    override fun now(): Instant = now
    override fun zone(): TimeZone = zone
}

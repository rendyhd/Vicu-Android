package com.rendyhd.vicu.util

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class ReviewState { NEVER, REVIEWED, EXCLUDED }

data class ReviewMetadata(
    val state: ReviewState,
    val lastReviewedAt: String?, // ISO local date "YYYY-MM-DD"
    val cadenceDaysOverride: Int?, // null = use global default
) {
    companion object {
        private const val PREFIX = "**Vicu review**:"
        private const val SEPARATOR = "---"
        private const val DOT = "·"

        private val MARKER_REGEX = Regex("(?:^|\\n)---\\s*\\n\\*\\*Vicu review\\*\\*:\\s*(.+?)\\s*$")
        private val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val CADENCE_REGEX =
            Regex("^every\\s+(\\d+)\\s*(d|day|days|w|week|weeks)$", RegexOption.IGNORE_CASE)

        fun parse(description: String?): ReviewMetadata {
            val default = ReviewMetadata(ReviewState.NEVER, null, null)
            if (description.isNullOrEmpty()) return default
            val match = MARKER_REGEX.find(description) ?: return default
            val raw = match.groupValues[1].trim()
            val parts = raw.split(DOT)
                .flatMap { it.split(" | ") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parts.isEmpty()) return default
            val head = parts[0]
            val (state, last) = when {
                head.equals("excluded", true) ->
                    return ReviewMetadata(ReviewState.EXCLUDED, null, null)
                head.equals("never", true) -> ReviewState.NEVER to null
                ISO_DATE.matches(head) -> ReviewState.REVIEWED to head
                else -> return default
            }
            var cadence: Int? = null
            if (parts.size > 1) {
                val cm = CADENCE_REGEX.find(parts[1])
                if (cm != null) {
                    val n = cm.groupValues[1].toIntOrNull()
                    val unit = cm.groupValues[2].lowercase()
                    if (n != null) cadence = if (unit.startsWith("w")) n * 7 else n
                }
            }
            return ReviewMetadata(state, last, cadence)
        }

        fun serialize(meta: ReviewMetadata): String {
            if (meta.state == ReviewState.EXCLUDED) return "$PREFIX excluded"
            val head = if (meta.state == ReviewState.REVIEWED && meta.lastReviewedAt != null) {
                meta.lastReviewedAt
            } else {
                "never"
            }
            val c = meta.cadenceDaysOverride
            return if (c != null && c > 0) "$PREFIX $head $DOT every $c days" else "$PREFIX $head"
        }

        fun stripFooter(description: String): String {
            val m = MARKER_REGEX.find(description) ?: return description.trimEnd()
            return description.substring(0, m.range.first).trimEnd()
        }

        fun upsert(description: String, meta: ReviewMetadata): String {
            val body = stripFooter(description)
            val footer = "$SEPARATOR\n${serialize(meta)}"
            return if (body.isEmpty()) footer else "$body\n\n$footer"
        }

        fun computeStatus(
            meta: ReviewMetadata,
            globalCadenceDays: Int,
            today: LocalDate = LocalDate.now(),
        ): ReviewStatus {
            val cadence = meta.cadenceDaysOverride ?: globalCadenceDays
            if (meta.state == ReviewState.EXCLUDED) {
                return ReviewStatus(meta, cadence, null, false, null, null)
            }
            val last = meta.lastReviewedAt?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (meta.state != ReviewState.REVIEWED || last == null) {
                return ReviewStatus(meta, cadence, null, true, null, null)
            }
            val next = last.plusDays(cadence.toLong())
            val daysSince = ChronoUnit.DAYS.between(last, today)
            val daysUntil = ChronoUnit.DAYS.between(today, next)
            return ReviewStatus(meta, cadence, next, daysUntil < 0, daysSince, daysUntil)
        }

        /** Local-calendar today as YYYY-MM-DD, used when marking reviewed. */
        fun todayLocalIsoDate(): String = LocalDate.now().toString()
    }
}

data class ReviewStatus(
    val metadata: ReviewMetadata,
    val effectiveCadenceDays: Int,
    val nextReviewAt: LocalDate?,
    val isOverdue: Boolean,
    val daysSinceReviewed: Long?,
    val daysUntilDue: Long?,
)

package com.rendyhd.vicu.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Colours outside the Material 3 scheme. The values are test-fixtures/design-tokens-v1.json
// (android.custom, roles priority.*, identity); DesignTokensTest fails when they drift.
// Overdue has no entry here: it stays colorScheme.error (android.statusMap).
// Docs: docs/design-system-v1.md, section 8.

/** A colour outside the scheme with its content colour and its container pair. */
@Immutable
class VicuColorRole(
    val color: Color,
    val onColor: Color,
    val container: Color,
    val onContainer: Color,
)

/** Smart list identity colours. They only ever colour list icons, never text. */
@Immutable
class VicuIdentityColors(
    val inbox: Color,
    val today: Color,
    val upcoming: Color,
    val anytime: Color,
    val routines: Color,
    val review: Color,
    val logbook: Color,
)

@Immutable
class VicuColors(
    val dueToday: VicuColorRole,
    val done: VicuColorRole,
    val swipeComplete: VicuColorRole,
    val swipeSchedule: VicuColorRole,
    val priorityLow: Color,
    val priorityMedium: Color,
    val priorityHigh: Color,
    val priorityUrgent: Color,
    val identity: VicuIdentityColors,
) {
    /** The colour of a Vikunja priority: 1 low, 2 medium, 3 high, 4 and 5 urgent; 0 has none. */
    fun priority(level: Int): Color? = when (level) {
        1 -> priorityLow
        2 -> priorityMedium
        3 -> priorityHigh
        4, 5 -> priorityUrgent
        else -> null
    }
}

val VicuLightColors = VicuColors(
    dueToday = VicuColorRole(
        color = Color(0xFF9A4600),
        onColor = Color(0xFFFFFFFF),
        container = Color(0xFFFFDBC9),
        onContainer = Color(0xFF321200),
    ),
    done = VicuColorRole(
        color = Color(0xFF1E7D34),
        onColor = Color(0xFFFFFFFF),
        container = Color(0xFF9AF89F),
        onContainer = Color(0xFF002106),
    ),
    swipeComplete = VicuColorRole(
        color = Color(0xFF026E27),
        onColor = Color(0xFFFFFFFF),
        container = Color(0xFF9AF89F),
        onContainer = Color(0xFF002106),
    ),
    swipeSchedule = VicuColorRole(
        color = Color(0xFFE99B00),
        onColor = Color(0xFF291800),
        container = Color(0xFFFFDDB4),
        onContainer = Color(0xFF291800),
    ),
    priorityLow = Color(0xFF0066CC),
    priorityMedium = Color(0xFF8A5F00),
    priorityHigh = Color(0xFFA74A09),
    priorityUrgent = Color(0xFFD70015),
    identity = VicuIdentityColors(
        inbox = Color(0xFF0A84FF),
        today = Color(0xFFE8A400),
        upcoming = Color(0xFFE5484D),
        anytime = Color(0xFF14A3A3),
        routines = Color(0xFFE5487A),
        review = Color(0xFF8E55E8),
        logbook = Color(0xFF2E9D58),
    ),
)

val VicuDarkColors = VicuColors(
    dueToday = VicuColorRole(
        color = Color(0xFFFFB870),
        onColor = Color(0xFF532200),
        container = Color(0xFF753400),
        onContainer = Color(0xFFFFDBC9),
    ),
    done = VicuColorRole(
        color = Color(0xFF30D158),
        onColor = Color(0xFF002106),
        container = Color(0xFF00531B),
        onContainer = Color(0xFF9AF89F),
    ),
    swipeComplete = VicuColorRole(
        color = Color(0xFF7FDB85),
        onColor = Color(0xFF002106),
        container = Color(0xFF00531B),
        onContainer = Color(0xFF9AF89F),
    ),
    swipeSchedule = VicuColorRole(
        color = Color(0xFFFFB952),
        onColor = Color(0xFF291800),
        container = Color(0xFF633F00),
        onContainer = Color(0xFFFFDDB4),
    ),
    priorityLow = Color(0xFF6AACFF),
    priorityMedium = Color(0xFFFFD60A),
    priorityHigh = Color(0xFFFF9F0A),
    priorityUrgent = Color(0xFFFF7B73),
    identity = VicuIdentityColors(
        inbox = Color(0xFF0A84FF),
        today = Color(0xFFE8A400),
        upcoming = Color(0xFFE5484D),
        anytime = Color(0xFF14A3A3),
        routines = Color(0xFFE5487A),
        review = Color(0xFF8E55E8),
        logbook = Color(0xFF2E9D58),
    ),
)

/** The Vicu colours of the current theme; [VicuTheme] provides them. */
val LocalVicuColors = staticCompositionLocalOf { VicuLightColors }

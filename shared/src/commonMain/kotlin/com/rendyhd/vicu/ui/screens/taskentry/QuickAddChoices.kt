package com.rendyhd.vicu.ui.screens.taskentry

import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.location.LocationTrigger
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.ReminderFormat
import com.rendyhd.vicu.util.WeekStart
import com.rendyhd.vicu.util.WhenLogic
import com.rendyhd.vicu.util.WhenQuickChoice
import com.rendyhd.vicu.util.WhenValue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

// What the trays of the new-task sheet offer (design concept 3): the When tiles and the words of
// the Remind chip. Pure, so the day and the clock are passed in.

/** One tile of the When tray. [value] is what a tap picks; null clears the date. */
internal data class WhenTile(val id: Id, val label: String, val hint: String, val value: WhenValue?) {
    enum class Id { TODAY, EVENING, TOMORROW, WEEKEND, NEXT_WEEK, NO_DATE }
}

/** "This evening" is today at this time; offered until it has passed. */
internal val EVENING_TIME = LocalTime(18, 0)

/**
 * The When tray's tiles at [now] on [today]: Today, This evening (until 18:00), Tomorrow, This
 * weekend, Next week and No date. A day tile keeps the time of day of [current] ("tomorrow, same
 * time"); This evening is today at 18:00. Hints are the weekday ("Wed", for next week "Mon 12")
 * and the evening's clock time, phrased with [fmt].
 */
internal fun whenTiles(
    current: WhenValue,
    today: LocalDate,
    now: LocalTime,
    fmt: DateDisplayFormat,
    weekStart: WeekStart = WeekStart.DEFAULT,
): List<WhenTile> {
    val keepTime = current.time?.takeIf { current.date != null }
    val choices = WhenLogic.quickChoices(today, weekStart).associateBy { it.id }
    fun day(id: WhenTile.Id, choice: WhenQuickChoice) =
        WhenTile(id, choice.label, WhenLogic.hint(choice, fmt), WhenValue(choice.date, keepTime))
    return buildList {
        add(day(WhenTile.Id.TODAY, choices.getValue(WhenQuickChoice.Id.TODAY)))
        if (now < EVENING_TIME) {
            add(WhenTile(WhenTile.Id.EVENING, "This evening", WhenLogic.timeLabel(EVENING_TIME, fmt), WhenValue(today, EVENING_TIME)))
        }
        add(day(WhenTile.Id.TOMORROW, choices.getValue(WhenQuickChoice.Id.TOMORROW)))
        add(day(WhenTile.Id.WEEKEND, choices.getValue(WhenQuickChoice.Id.WEEKEND)))
        add(day(WhenTile.Id.NEXT_WEEK, choices.getValue(WhenQuickChoice.Id.NEXT_WEEK)))
        add(WhenTile(WhenTile.Id.NO_DATE, "No date", "", null))
    }
}

/**
 * The tile that stands for [current], or null when none does (a date picked on the calendar).
 * This evening is today at 18:00; Today is today at any other time or none; the other days match
 * by date. The first matching tile wins, so a Saturday that is both Tomorrow and This weekend is
 * Tomorrow.
 */
internal fun selectedTile(tiles: List<WhenTile>, current: WhenValue, today: LocalDate): WhenTile.Id? {
    val date = current.date ?: return WhenTile.Id.NO_DATE.takeIf { tiles.any { it.id == WhenTile.Id.NO_DATE } }
    val evening = date == today && current.time == EVENING_TIME
    return tiles.firstOrNull { tile ->
        when (tile.id) {
            WhenTile.Id.EVENING -> evening
            WhenTile.Id.TODAY -> date == today && !evening
            WhenTile.Id.NO_DATE -> false
            else -> tile.value?.date == date
        }
    }?.id
}

/**
 * The Remind chip: the one reminder in words ("Tomorrow, 9:00 AM", "1 hour before", "Arrive:
 * Home"), "3 reminders" when there are more, null when there are none (the chip then says
 * "Remind"). The place reminder counts as one.
 */
internal fun entryRemindWords(reminders: List<TaskReminder>, place: PlacePick?, fmt: DateDisplayFormat): String? =
    ReminderFormat.summary(reminders, fmt, place?.let { entryPlaceLabel(it.place.name, it.trigger) }).ifEmpty { null }

/** "Arrive: Home" or "Leave: Work", as a location reminder reads everywhere else. */
internal fun entryPlaceLabel(name: String, trigger: LocationTrigger): String = when (trigger) {
    LocationTrigger.ARRIVE -> "Arrive: $name"
    LocationTrigger.LEAVE -> "Leave: $name"
}

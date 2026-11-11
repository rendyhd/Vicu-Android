package com.rendyhd.vicu.util.ics

import com.rendyhd.vicu.util.IcsImport
import com.rendyhd.vicu.util.PlanEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** The iCalendar reader: lines, rules (RFC 5545's own examples), zones and the instances of a series. */
class IcsReaderTest {
    private fun file(vararg lines: String) =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0") + lines + "END:VCALENDAR").joinToString("\r\n")

    private fun expand(rule: String, start: String, stop: String = "2100-01-01T00:00", isDate: Boolean = false): List<String> {
        val recur = assertNotNull(IcsRecur.parse(rule, null))
        val until = recur.until?.wall
        return IcsRecurrence.expand(recur, LocalDateTime.parse(start), isDate, until, LocalDateTime.parse(stop))
            .map { if (isDate) it.date.toString() else it.toString() }
    }

    @Test
    fun foldedLinesAndEscapesAreRead() {
        val roots = IcsText.parse("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nSUMMARY:Lunch\\, then\r\n  a walk\\; maybe\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
        val event = roots.single().children("VEVENT").single()
        assertEquals("Lunch, then a walk; maybe", event.text("SUMMARY"))
    }

    @Test
    fun quotedParametersKeepTheirColons() {
        val property = assertNotNull(IcsText.parseLine("DTSTART;TZID=\"(UTC+01:00) Amsterdam, Berlin\":20261112T093000"))
        assertEquals("(UTC+01:00) Amsterdam, Berlin", property.param("TZID"))
        assertEquals("20261112T093000", property.value)
    }

    @Test
    fun weeklyEveryOtherWeekDependsOnWkst() {
        // RFC 5545 3.8.5.3, the WKST example.
        assertEquals(
            listOf("1997-08-05T09:00", "1997-08-10T09:00", "1997-08-19T09:00", "1997-08-24T09:00"),
            expand("FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=TU,SU;WKST=MO", "1997-08-05T09:00"),
        )
        assertEquals(
            listOf("1997-08-05T09:00", "1997-08-17T09:00", "1997-08-19T09:00", "1997-08-31T09:00"),
            expand("FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=TU,SU;WKST=SU", "1997-08-05T09:00"),
        )
    }

    @Test
    fun theLastWorkdayOfTheMonth() {
        assertEquals(
            listOf("1997-09-30T09:00", "1997-10-31T09:00", "1997-11-28T09:00"),
            expand("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=3", "1997-09-30T09:00"),
        )
    }

    @Test
    fun yearlyByWeekNumberAndNthWeekdayOfAMonth() {
        assertEquals(
            listOf("1997-05-12T09:00", "1998-05-11T09:00", "1999-05-17T09:00"),
            expand("FREQ=YEARLY;BYWEEKNO=20;BYDAY=MO;COUNT=3", "1997-05-12T09:00"),
        )
        assertEquals(
            listOf("2026-11-26", "2027-11-25", "2028-11-23"),
            expand("FREQ=YEARLY;BYMONTH=11;BYDAY=4TH;COUNT=3", "2026-11-26T00:00", isDate = true),
        )
    }

    @Test
    fun negativeMonthDaysAndMonthsWithoutTheDate() {
        assertEquals(
            listOf("2026-01-31T08:00", "2026-02-28T08:00", "2026-03-31T08:00"),
            expand("FREQ=MONTHLY;BYMONTHDAY=-1;COUNT=3", "2026-01-31T08:00"),
        )
        // The 31st skips the months without one.
        assertEquals(
            listOf("2026-01-31T08:00", "2026-03-31T08:00", "2026-05-31T08:00"),
            expand("FREQ=MONTHLY;COUNT=3", "2026-01-31T08:00"),
        )
    }

    @Test
    fun untilEndsTheSeriesAndDtstartIsAlwaysTheFirst() {
        assertEquals(
            listOf("2026-11-01T10:00", "2026-11-02T10:00", "2026-11-03T10:00"),
            expand("FREQ=DAILY;UNTIL=20261103T100000", "2026-11-01T10:00"),
        )
        // A Wednesday start of a Monday rule: DTSTART still comes first, and counts.
        assertEquals(
            listOf("2026-11-04T10:00", "2026-11-09T10:00"),
            expand("FREQ=WEEKLY;BYDAY=MO;COUNT=2", "2026-11-04T10:00"),
        )
    }

    @Test
    fun aRuleThatGivesNothingStops() {
        assertEquals(listOf("2026-01-30T08:00"), expand("FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=30", "2026-01-30T08:00", "2400-01-01T00:00"))
    }

    private fun importOne(text: String, zone: String = "UTC") =
        IcsImport.importText(text, LocalDate(2026, 11, 10), TimeZone.of(zone), busyOnly = false).map { PlanEvents.encode(it).toString() }

    @Test
    fun zonesTheFileOnlyNames() {
        // A Windows name without a VTIMEZONE: New York. An IANA id. An unknown id: local time as written.
        val text = file(
            "BEGIN:VEVENT", "UID:a", "SUMMARY:A", "DTSTART;TZID=Eastern Standard Time:20261112T090000", "DURATION:PT1H", "END:VEVENT",
            "BEGIN:VEVENT", "UID:b", "SUMMARY:B", "DTSTART;TZID=/mozilla.org/20050126_1/Europe/Berlin:20261112T090000", "DTEND;TZID=Europe/Berlin:20261112T093000", "END:VEVENT",
            "BEGIN:VEVENT", "UID:c", "SUMMARY:C", "DTSTART;TZID=Somewhere Else:20261112T090000", "DTEND;TZID=Somewhere Else:20261112T091500", "END:VEVENT",
        )
        assertEquals(
            listOf(
                """{"title":"B","start":"2026-11-12","time":"08:00","minutes":30}""",
                """{"title":"C","start":"2026-11-12","time":"09:00","minutes":15}""",
                """{"title":"A","start":"2026-11-12","time":"14:00","minutes":60}""",
            ),
            importOne(text),
        )
    }

    @Test
    fun aVtimezoneFollowsItsSummerTime() {
        val zone = listOf(
            "BEGIN:VTIMEZONE", "TZID:Office",
            "BEGIN:STANDARD", "DTSTART:16010101T030000", "TZOFFSETFROM:+0200", "TZOFFSETTO:+0100", "RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=10", "END:STANDARD",
            "BEGIN:DAYLIGHT", "DTSTART:16010101T020000", "TZOFFSETFROM:+0100", "TZOFFSETTO:+0200", "RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=3", "END:DAYLIGHT",
            "END:VTIMEZONE",
        )
        val calendar = IcsCalendar.read(file(*zone.toTypedArray()), TimeZone.UTC)
        assertEquals(0, calendar.events.size)
        val tz = assertNotNull(VTimeZone.of(IcsText.parse(zone.joinToString("\r\n")).single()))
        // Summer: UTC+2; winter: UTC+1; the change on 25 October 2026 at 03:00 local.
        assertEquals(LocalDateTime(2026, 7, 1, 7, 30).toInstant(TimeZone.UTC), tz.toInstant(LocalDateTime(2026, 7, 1, 9, 30)))
        assertEquals(LocalDateTime(2026, 11, 12, 8, 30).toInstant(TimeZone.UTC), tz.toInstant(LocalDateTime(2026, 11, 12, 9, 30)))
        assertEquals(LocalDateTime(2026, 10, 25, 0, 30).toInstant(TimeZone.UTC), tz.toInstant(LocalDateTime(2026, 10, 25, 2, 30)))
        // The gap on 29 March 2026 (02:00 to 03:00): 02:30 lands after it, as java.time does.
        assertEquals(LocalDateTime(2026, 3, 29, 1, 30).toInstant(TimeZone.UTC), tz.toInstant(LocalDateTime(2026, 3, 29, 2, 30)))
    }

    @Test
    fun exdatesInAnotherZoneAndDateOnlyAreLeftOut() {
        val text = file(
            "BEGIN:VEVENT", "UID:x", "SUMMARY:Shift", "DTSTART;TZID=Europe/Berlin:20261111T090000", "DTEND;TZID=Europe/Berlin:20261111T100000",
            "RRULE:FREQ=DAILY;BYHOUR=9,15;COUNT=4", "EXDATE:20261111T140000Z", "EXDATE;VALUE=DATE:20261112", "END:VEVENT",
        )
        assertEquals(
            listOf("""{"title":"Shift","start":"2026-11-11","time":"08:00","minutes":60}"""),
            importOne(text),
        )
    }

    @Test
    fun anEventWithoutAnEndAndAnOverrideWithoutItsSeries() {
        val text = file(
            "BEGIN:VEVENT", "UID:z", "SUMMARY:Ping", "DTSTART:20261111T090000Z", "END:VEVENT",
            "BEGIN:VEVENT", "UID:gone", "RECURRENCE-ID:20261112T090000Z", "SUMMARY:Moved", "DTSTART:20261112T110000Z", "DTEND:20261112T113000Z", "END:VEVENT",
        )
        assertEquals(
            listOf(
                """{"title":"Ping","start":"2026-11-11","time":"09:00","minutes":1}""",
                """{"title":"Moved","start":"2026-11-12","time":"11:00","minutes":30}""",
            ),
            importOne(text),
        )
    }

    @Test
    fun windowsNamesAndDisplayNames() {
        assertEquals("Europe/Berlin", IcsZones.ianaZoneFor("W. Europe Standard Time"))
        assertEquals("Europe/Berlin", IcsZones.ianaZoneFor("(UTC+01:00) Amsterdam, Berlin, Bern, Rome, Stockholm, Vienna"))
        assertEquals("America/New_York", IcsZones.ianaZoneFor("\"America/New_York\""))
        assertNull(IcsZones.ianaZoneFor("Nowhere Standard Time"))
    }
}

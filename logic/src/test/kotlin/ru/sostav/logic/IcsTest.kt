package ru.sostav.logic

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IcsTest {
    @Test
    fun yandexTimedEventKeepsMoscowTimeAndPlace() {
        val events = parseIcs(
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:concert-1@yandex.ru
            DTSTART;TZID=Europe/Moscow:20261107T190000
            DTEND;TZID=Europe/Moscow:20261107T220000
            SUMMARY:Концерт в клубе
            LOCATION:Москва\, Клуб16
            DESCRIPTION:Сбор в 18:00
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        )
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("Концерт в клубе", event.title)
        assertEquals(LocalDate.of(2026, 11, 7), event.date)
        assertEquals(LocalDate.of(2026, 11, 7), event.endDate)
        assertEquals("19:00–22:00", event.timeText)
        assertEquals(19, event.hour)
        assertEquals("Москва, Клуб16", event.location)
        val draft = toConcertDraft(event, "ivan")
        assertEquals("Москва", draft.city)
        assertEquals("Клуб16", draft.venue)
        assertEquals("19:00–22:00 · Сбор в 18:00", draft.note)
        assertEquals(ConcertStatus.CONFIRMED, draft.status)
        assertEquals("concert-1@yandex.ru/2026-11-07", draft.importUid)
    }

    @Test
    fun allDayRangeIsInclusive() {
        val events = parseIcs(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:trip
            DTSTART;VALUE=DATE:20261104
            DTEND;VALUE=DATE:20261111
            SUMMARY:Макс минус
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        )
        val event = events.single()
        assertEquals(LocalDate.of(2026, 11, 4), event.date)
        assertEquals(LocalDate.of(2026, 11, 10), event.endDate)
        assertEquals("", event.timeText)
    }

    @Test
    fun foldedSummaryAndEscapes() {
        val events = parseIcs(
            "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:fold\nDTSTART;VALUE=DATE:20261027\nSUMMARY:Очень длинное\n  название\nDESCRIPTION:строка\\nвторая\nEND:VEVENT\nEND:VCALENDAR\n",
        )
        assertEquals("Очень длинное название", events.single().title)
        assertEquals("строка\nвторая", events.single().description)
    }

    @Test
    fun weeklyRuleExpandsAndSkipsExdate() {
        val events = parseIcs(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:rehearsal
            DTSTART;TZID=Europe/Moscow:20261026T180000
            DTEND;TZID=Europe/Moscow:20261026T210000
            RRULE:FREQ=WEEKLY;COUNT=4;BYDAY=MO
            EXDATE;TZID=Europe/Moscow:20261102T180000
            SUMMARY:Репетиция
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                LocalDate.of(2026, 10, 26),
                LocalDate.of(2026, 11, 9),
                LocalDate.of(2026, 11, 16),
            ),
            events.map { it.date },
        )
    }

    @Test
    fun duplicateMatchesUidAndTitleDate() {
        val event = parseIcs(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:same
            DTSTART;VALUE=DATE:20261107
            SUMMARY:Гига
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        ).single()
        val byUid = Concert(
            "1", "Гига", event.date, event.endDate, DayPart.EVENING, "", "", "",
            ConcertStatus.CONFIRMED, "ivan", event.uid,
        )
        val byTitle = byUid.copy(id = "2", importUid = "")
        assertTrue(alreadyImported(event, listOf(byUid)))
        assertTrue(alreadyImported(event, listOf(byTitle)))
        assertFalse(alreadyImported(event, listOf(byUid.copy(title = "Другое", importUid = ""))))
    }

    @Test
    fun weeklyWithoutExceptionsStaysOneRepeatingEvent() {
        val events = parseIcs(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:rehearsal-simple
            DTSTART;TZID=Europe/Moscow:20261027T180000
            DTEND;TZID=Europe/Moscow:20261027T210000
            RRULE:FREQ=WEEKLY;BYDAY=TU
            SUMMARY:Репетиция
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        )
        val event = events.single()
        assertEquals(RepeatPeriod.WEEKLY, event.repeat)
        assertEquals(LocalDate.of(2026, 10, 27), event.date)
        assertEquals("18:00–21:00", event.timeText)
    }

    @Test
    fun monthlyFourthFriday() {
        val events = parseIcs(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:rent
            DTSTART;VALUE=DATE:20260828
            DTEND;VALUE=DATE:20260829
            RRULE:FREQ=MONTHLY;BYDAY=4FR;INTERVAL=1;COUNT=4
            SUMMARY:Аренда
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                LocalDate.of(2026, 8, 28),
                LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 10, 23),
                LocalDate.of(2026, 11, 27),
            ),
            events.map { it.date },
        )
    }
}

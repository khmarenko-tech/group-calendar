package ru.sostav.logic

import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleTest {
    private val ivan = "ivan"
    private val day = LocalDate.of(2026, 10, 26)

    @Test
    fun october2026StartsOnThursday() {
        val grid = monthGrid(YearMonth.of(2026, 10))
        assertNull(grid[0])
        assertNull(grid[2])
        assertEquals(LocalDate.of(2026, 10, 1), grid[3])
        assertEquals(LocalDate.of(2026, 10, 26), grid[3 + 25])
        assertEquals(LocalDate.of(2026, 10, 31), grid[33])
        assertEquals(35, grid.size)
        assertNull(grid.last())
    }

    @Test
    fun mondayMonthHasNoLeadingBlanks() {
        val grid = monthGrid(YearMonth.of(2026, 6))
        assertEquals(LocalDate.of(2026, 6, 1), grid.first())
    }

    @Test
    fun rangeMarksEveryDay() {
        val vacation = absence(
            start = LocalDate.of(2026, 11, 4),
            end = LocalDate.of(2026, 11, 10),
            note = "Макс минус",
        )
        assertTrue(vacation.covers(LocalDate.of(2026, 11, 4)))
        assertTrue(vacation.covers(LocalDate.of(2026, 11, 7)))
        assertTrue(vacation.covers(LocalDate.of(2026, 11, 10)))
        assertEquals(false, vacation.covers(LocalDate.of(2026, 11, 3)))
        assertEquals(false, vacation.covers(LocalDate.of(2026, 11, 11)))
        assertEquals("Макс минус", absenceLabel(vacation, "Максим"))
    }

    @Test
    fun emptyNoteBecomesNameMinus() {
        assertEquals("Макс минус", absenceLabel(absence(status = Availability.CANNOT), "Макс Орифович"))
        assertEquals("Макс ?", absenceLabel(absence(status = Availability.MAYBE), "Макс Орифович"))
    }

    @Test
    fun cannotBeatsMaybeAndCan() {
        val absences = listOf(
            absence(status = Availability.CAN, note = "могу"),
            absence(status = Availability.MAYBE, id = "m"),
            absence(status = Availability.CANNOT, id = "n", note = "смена"),
        )
        val winning = winningAbsence(absences, ivan, day)
        assertEquals(Availability.CANNOT, winning?.status)
        assertEquals("смена", winning?.note)
    }

    @Test
    fun rangeIsNotAnExactDay() {
        val range = absence(start = day, end = day.plusDays(2))
        assertNull(exactDay(listOf(range), ivan, day))
        val exact = absence(id = "one", start = day, end = day)
        assertEquals("one", exactDay(listOf(range, exact), ivan, day)?.id)
    }

    @Test
    fun blockingListKeepsOneMarkPerPerson() {
        val absences = listOf(
            absence(uid = ivan, status = Availability.CANNOT),
            absence(uid = ivan, id = "2", status = Availability.CANNOT),
            absence(uid = "anna", id = "3", status = Availability.MAYBE),
            absence(uid = "oleg", id = "4", status = Availability.CAN),
        )
        val blocking = blockingAbsences(absences, day)
        assertEquals(listOf(ivan), blocking.map { it.uid })
    }

    @Test
    fun cancelledConcertIsNotActive() {
        val concerts = listOf(
            concert("1", "Клуб", day, ConcertStatus.CANCELLED),
            concert("2", "Фест", day.plusDays(1), ConcertStatus.IDEA),
        )
        assertEquals(listOf("2"), activeConcertsInRange(concerts, day, day.plusDays(3)).map { it.id })
    }

    @Test
    fun multiDayEventShowsOnEveryDay() {
        val event = concert("k", "Курок", LocalDate.of(2026, 10, 16), ConcertStatus.CONFIRMED, LocalDate.of(2026, 10, 17))
        assertTrue(event.covers(LocalDate.of(2026, 10, 16)))
        assertTrue(event.covers(LocalDate.of(2026, 10, 17)))
        assertEquals(false, event.covers(LocalDate.of(2026, 10, 15)))
        assertEquals(listOf("k"), concertsOn(listOf(event), LocalDate.of(2026, 10, 17)).map { it.id })
    }

    @Test
    fun weeklyEventHitsSameWeekday() {
        val rehearsal = concert("r", "Репетиция", LocalDate.of(2026, 10, 27), ConcertStatus.CONFIRMED)
            .copy(repeat = RepeatPeriod.WEEKLY, repeatUntil = LocalDate.of(2026, 11, 17))
        assertTrue(rehearsal.covers(LocalDate.of(2026, 10, 27)))
        assertTrue(rehearsal.covers(LocalDate.of(2026, 11, 3)))
        assertTrue(rehearsal.covers(LocalDate.of(2026, 11, 17)))
        assertEquals(false, rehearsal.covers(LocalDate.of(2026, 10, 28)))
        assertEquals(false, rehearsal.covers(LocalDate.of(2026, 11, 24)))
        assertEquals(listOf("r"), activeConcertsInRange(listOf(rehearsal), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10)).map { it.id })
    }

    @Test
    fun monthlySkipsShortMonths() {
        val payday = concert("p", "Оплата", LocalDate.of(2026, 1, 31), ConcertStatus.CONFIRMED)
            .copy(repeat = RepeatPeriod.MONTHLY)
        assertTrue(payday.covers(LocalDate.of(2026, 1, 31)))
        assertEquals(false, payday.covers(LocalDate.of(2026, 2, 28)))
        assertTrue(payday.covers(LocalDate.of(2026, 3, 31)))
    }

    @Test
    fun yearlyEvent() {
        val fest = concert("f", "Фест", LocalDate.of(2026, 8, 4), ConcertStatus.CONFIRMED)
            .copy(repeat = RepeatPeriod.YEARLY)
        assertTrue(fest.covers(LocalDate.of(2027, 8, 4)))
        assertEquals(false, fest.covers(LocalDate.of(2027, 8, 5)))
    }

    @Test
    fun seriesCutKeepsTheRest() {
        val rehearsal = concert("r", "Репетиция", LocalDate.of(2026, 10, 6), ConcertStatus.CONFIRMED)
            .copy(repeat = RepeatPeriod.WEEKLY, repeatUntil = LocalDate.of(2026, 11, 3))
        val only = rehearsal.afterCut(LocalDate.of(2026, 10, 20), SeriesCut.ONLY_THIS)
        assertEquals(false, only!!.covers(LocalDate.of(2026, 10, 20)))
        assertTrue(only.covers(LocalDate.of(2026, 10, 13)))
        assertTrue(only.covers(LocalDate.of(2026, 10, 27)))

        val following = rehearsal.afterCut(LocalDate.of(2026, 10, 20), SeriesCut.FOLLOWING)
        assertTrue(following!!.covers(LocalDate.of(2026, 10, 13)))
        assertEquals(false, following.covers(LocalDate.of(2026, 10, 20)))
        assertEquals(false, following.covers(LocalDate.of(2026, 10, 27)))

        val previous = rehearsal.afterCut(LocalDate.of(2026, 10, 20), SeriesCut.PREVIOUS)
        assertEquals(false, previous!!.covers(LocalDate.of(2026, 10, 6)))
        assertEquals(false, previous.covers(LocalDate.of(2026, 10, 20)))
        assertTrue(previous.covers(LocalDate.of(2026, 10, 27)))
        assertEquals(null, rehearsal.afterCut(LocalDate.of(2026, 10, 6), SeriesCut.FOLLOWING))
        assertEquals(null, rehearsal.afterCut(LocalDate.of(2026, 11, 3), SeriesCut.PREVIOUS))
        assertEquals(null, rehearsal.afterCut(LocalDate.of(2026, 10, 20), SeriesCut.ALL))
    }

    @Test
    fun official2027Calendar() {
        assertEquals("Новогодние каникулы", officialDay(LocalDate.of(2027, 1, 1))?.title)
        assertEquals(OfficialKind.HOLIDAY, officialDay(LocalDate.of(2027, 1, 2))?.kind)
        assertEquals(OfficialKind.HOLIDAY, officialDay(LocalDate.of(2027, 1, 7))?.kind)
        assertEquals("Выходной", officialDay(LocalDate.of(2027, 1, 9))?.title)
        assertEquals(OfficialKind.SHORT, officialDay(LocalDate.of(2027, 2, 20))?.kind)
        assertEquals(false, isOfficialDayOff(LocalDate.of(2027, 2, 20)))
        assertEquals(OfficialKind.DAY_OFF, officialDay(LocalDate.of(2027, 2, 22))?.kind)
        assertEquals("День защитника Отечества", officialDay(LocalDate.of(2027, 2, 23))?.title)
        assertEquals(OfficialKind.DAY_OFF, officialDay(LocalDate.of(2027, 5, 3))?.kind)
        assertEquals("День Победы", officialDay(LocalDate.of(2027, 5, 9))?.title)
        assertEquals(OfficialKind.DAY_OFF, officialDay(LocalDate.of(2027, 5, 10))?.kind)
        assertEquals(OfficialKind.DAY_OFF, officialDay(LocalDate.of(2027, 6, 14))?.kind)
        assertEquals(OfficialKind.DAY_OFF, officialDay(LocalDate.of(2027, 11, 5))?.kind)
        assertEquals(OfficialKind.DAY_OFF, officialDay(LocalDate.of(2027, 12, 31))?.kind)
        assertEquals(OfficialKind.DAY_OFF, officialDay(LocalDate.of(2026, 12, 31))?.kind)
        assertEquals(false, isOfficialDayOff(LocalDate.of(2027, 7, 6)))
        assertTrue(isOfficialDayOff(LocalDate.of(2027, 7, 3)))
    }

    @Test
    fun dailyStopsAtUntil() {
        val camp = concert("c", "Сбор", LocalDate.of(2026, 10, 27), ConcertStatus.CONFIRMED)
            .copy(repeat = RepeatPeriod.DAILY, repeatUntil = LocalDate.of(2026, 10, 29))
        assertTrue(camp.covers(LocalDate.of(2026, 10, 29)))
        assertEquals(false, camp.covers(LocalDate.of(2026, 10, 30)))
    }

    private fun absence(
        id: String = "a",
        uid: String = ivan,
        start: LocalDate = day,
        end: LocalDate = day,
        parts: Set<DayPart> = allDayParts(),
        status: Availability = Availability.CANNOT,
        note: String = "",
    ) = Absence(id, uid, start, end, parts, status, note)

    private fun concert(
        id: String,
        title: String,
        date: LocalDate,
        status: ConcertStatus,
        endDate: LocalDate = date,
    ) = Concert(id, title, date, endDate, DayPart.EVENING, "", "", "", status, ivan)
}

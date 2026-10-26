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
    fun rangeIsInclusiveAndRespectsPart() {
        val vacation = absence(
            start = LocalDate.of(2026, 11, 1),
            end = LocalDate.of(2026, 11, 14),
            parts = setOf(DayPart.EVENING),
            status = Availability.CANNOT,
        )
        assertTrue(vacation.covers(LocalDate.of(2026, 11, 1), DayPart.EVENING))
        assertTrue(vacation.covers(LocalDate.of(2026, 11, 14), DayPart.EVENING))
        assertEquals(false, vacation.covers(LocalDate.of(2026, 11, 14), DayPart.MORNING))
        assertEquals(false, vacation.covers(LocalDate.of(2026, 10, 31), DayPart.EVENING))
    }

    @Test
    fun cannotBeatsMaybeAndCan() {
        val absences = listOf(
            absence(status = Availability.CAN, note = "могу"),
            absence(status = Availability.MAYBE, id = "m"),
            absence(status = Availability.CANNOT, id = "n", note = "смена"),
        )
        val winning = winningAbsence(absences, ivan, day, DayPart.EVENING)
        assertEquals(Availability.CANNOT, winning?.status)
        assertEquals("смена", winning?.note)
    }

    @Test
    fun exactSlotDoesNotMatchWiderRange() {
        val range = absence(
            start = day,
            end = day.plusDays(2),
            parts = setOf(DayPart.EVENING),
        )
        assertNull(exactSlot(listOf(range), ivan, day, DayPart.EVENING))
        val exact = absence(id = "one", start = day, end = day, parts = setOf(DayPart.EVENING))
        assertEquals("one", exactSlot(listOf(range, exact), ivan, day, DayPart.EVENING)?.id)
    }

    @Test
    fun blockingListKeepsOneMarkPerPerson() {
        val absences = listOf(
            absence(uid = ivan, status = Availability.CANNOT),
            absence(uid = ivan, id = "2", status = Availability.CANNOT),
            absence(uid = "anna", id = "3", status = Availability.MAYBE),
            absence(uid = "oleg", id = "4", status = Availability.CAN),
        )
        val blocking = blockingAbsences(absences, day, DayPart.EVENING)
        assertEquals(listOf(ivan), blocking.map { it.uid })
    }

    @Test
    fun cancelledConcertIsNotActive() {
        val concerts = listOf(
            Concert("1", "Клуб", day, DayPart.EVENING, "Тула", "Дворец", "", ConcertStatus.CANCELLED, ivan),
            Concert("2", "Фест", day.plusDays(1), DayPart.EVENING, "Москва", "", "", ConcertStatus.IDEA, ivan),
        )
        assertEquals(listOf("2"), activeConcertsInRange(concerts, day, day.plusDays(3)).map { it.id })
    }

    private fun absence(
        id: String = "a",
        uid: String = ivan,
        start: LocalDate = day,
        end: LocalDate = day,
        parts: Set<DayPart> = setOf(DayPart.EVENING),
        status: Availability = Availability.CANNOT,
        note: String = "",
    ) = Absence(id, uid, start, end, parts, status, note)
}

package ru.sostav.logic

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

enum class DayPart {
    MORNING,
    AFTERNOON,
    EVENING,
}

enum class Availability {
    CAN,
    CANNOT,
    MAYBE,
}

enum class ConcertStatus {
    IDEA,
    CONFIRMED,
    CANCELLED,
}

enum class Rsvp {
    GOING,
    NOT_GOING,
    MAYBE,
}

data class Absence(
    val id: String,
    val uid: String,
    val start: LocalDate,
    val end: LocalDate,
    val parts: Set<DayPart>,
    val status: Availability,
    val note: String,
) {
    fun covers(date: LocalDate, part: DayPart): Boolean {
        if (end.isBefore(start) || part !in parts) return false
        return !date.isBefore(start) && !date.isAfter(end)
    }

    fun isExactSlot(date: LocalDate, part: DayPart): Boolean {
        return start == date && end == date && parts == setOf(part)
    }
}

data class Concert(
    val id: String,
    val title: String,
    val date: LocalDate,
    val part: DayPart,
    val city: String,
    val venue: String,
    val note: String,
    val status: ConcertStatus,
    val createdBy: String,
)

fun monthGrid(month: YearMonth): List<LocalDate?> {
    val first = month.atDay(1)
    val lead = first.dayOfWeek.value - DayOfWeek.MONDAY.value
    val days = (1..month.lengthOfMonth()).map { month.atDay(it) }
    val cells: List<LocalDate?> = List(lead) { null } + days
    val trailing = (7 - cells.size % 7) % 7
    return cells + List(trailing) { null }
}

fun exactSlot(absences: List<Absence>, uid: String, date: LocalDate, part: DayPart): Absence? {
    return absences.find { it.uid == uid && it.isExactSlot(date, part) }
}

fun winningAbsence(absences: List<Absence>, uid: String, date: LocalDate, part: DayPart): Absence? {
    val matches = absences.filter { it.uid == uid && it.covers(date, part) }
    return matches.firstOrNull { it.status == Availability.CANNOT }
        ?: matches.firstOrNull { it.status == Availability.MAYBE }
        ?: matches.firstOrNull { it.status == Availability.CAN }
}

fun blockingAbsences(absences: List<Absence>, date: LocalDate, part: DayPart): List<Absence> {
    return absences
        .filter { it.covers(date, part) && it.status == Availability.CANNOT }
        .distinctBy { it.uid }
}

fun concertsOn(concerts: List<Concert>, date: LocalDate, part: DayPart? = null): List<Concert> {
    return concerts.filter { concert ->
        concert.date == date && (part == null || concert.part == part)
    }
}

fun activeConcertsInRange(concerts: List<Concert>, start: LocalDate, end: LocalDate): List<Concert> {
    if (end.isBefore(start)) return emptyList()
    return concerts.filter { concert ->
        concert.status != ConcertStatus.CANCELLED &&
            !concert.date.isBefore(start) &&
            !concert.date.isAfter(end)
    }
}

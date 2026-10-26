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
    fun covers(date: LocalDate): Boolean {
        if (end.isBefore(start)) return false
        return !date.isBefore(start) && !date.isAfter(end)
    }

    fun covers(date: LocalDate, part: DayPart): Boolean = covers(date)

    fun isExactDay(date: LocalDate): Boolean = start == date && end == date
}

data class Concert(
    val id: String,
    val title: String,
    val date: LocalDate,
    val endDate: LocalDate,
    val part: DayPart,
    val city: String,
    val venue: String,
    val note: String,
    val status: ConcertStatus,
    val createdBy: String,
) {
    val lastDay: LocalDate get() = if (endDate.isBefore(date)) date else endDate

    fun covers(date: LocalDate): Boolean = !date.isBefore(this.date) && !date.isAfter(lastDay)
}

fun monthGrid(month: YearMonth): List<LocalDate?> {
    val first = month.atDay(1)
    val lead = first.dayOfWeek.value - DayOfWeek.MONDAY.value
    val days = (1..month.lengthOfMonth()).map { month.atDay(it) }
    val cells: List<LocalDate?> = List(lead) { null } + days
    val trailing = (7 - cells.size % 7) % 7
    return cells + List(trailing) { null }
}

fun exactDay(absences: List<Absence>, uid: String, date: LocalDate): Absence? {
    return absences.find { it.uid == uid && it.isExactDay(date) }
}

fun exactSlot(absences: List<Absence>, uid: String, date: LocalDate, part: DayPart): Absence? {
    return exactDay(absences, uid, date)
}

fun winningAbsence(absences: List<Absence>, uid: String, date: LocalDate): Absence? {
    val matches = absences.filter { it.uid == uid && it.covers(date) }
    return matches.firstOrNull { it.status == Availability.CANNOT }
        ?: matches.firstOrNull { it.status == Availability.MAYBE }
        ?: matches.firstOrNull { it.status == Availability.CAN }
}

fun winningAbsence(absences: List<Absence>, uid: String, date: LocalDate, part: DayPart): Absence? {
    return winningAbsence(absences, uid, date)
}

fun blockingAbsences(absences: List<Absence>, date: LocalDate): List<Absence> {
    return absences
        .filter { it.covers(date) && it.status == Availability.CANNOT }
        .distinctBy { it.uid }
}

fun blockingAbsences(absences: List<Absence>, date: LocalDate, part: DayPart): List<Absence> {
    return blockingAbsences(absences, date)
}

fun concertsOn(concerts: List<Concert>, date: LocalDate): List<Concert> {
    return concerts.filter { it.covers(date) }
}

fun concertsOn(concerts: List<Concert>, date: LocalDate, part: DayPart?): List<Concert> {
    return concertsOn(concerts, date)
}

fun activeConcertsInRange(concerts: List<Concert>, start: LocalDate, end: LocalDate): List<Concert> {
    if (end.isBefore(start)) return emptyList()
    return concerts.filter { concert ->
        concert.status != ConcertStatus.CANCELLED &&
            !concert.lastDay.isBefore(start) &&
            !concert.date.isAfter(end)
    }
}

fun absenceLabel(absence: Absence, displayName: String): String {
    if (absence.note.isNotBlank()) return absence.note
    val short = displayName.substringBefore(" ").ifBlank { displayName }
    return when (absence.status) {
        Availability.CANNOT -> "$short минус"
        Availability.MAYBE -> "$short ?"
        Availability.CAN -> short
    }
}

fun allDayParts(): Set<DayPart> = DayPart.entries.toSet()

fun daysIn(start: LocalDate, end: LocalDate): List<LocalDate> {
    if (end.isBefore(start)) return emptyList()
    return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
}

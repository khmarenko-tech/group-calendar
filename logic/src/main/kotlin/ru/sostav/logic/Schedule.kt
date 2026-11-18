package ru.sostav.logic

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

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

enum class RepeatPeriod {
    NONE,
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
}

enum class Rsvp {
    GOING,
    NOT_GOING,
    MAYBE,
}

val eventColorKeys = setOf("blue", "green", "red", "orange", "purple", "pink", "teal", "slate")

enum class SeriesCut {
    ONLY_THIS,
    FOLLOWING,
    PREVIOUS,
    ALL,
}

enum class OfficialKind {
    HOLIDAY,
    DAY_OFF,
    SHORT,
}

data class OfficialDay(val title: String, val kind: OfficialKind)

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
    val importUid: String = "",
    val repeat: RepeatPeriod = RepeatPeriod.NONE,
    val repeatUntil: LocalDate? = null,
    val color: String = "",
    val exceptDates: Set<LocalDate> = emptySet(),
) {
    val lastDay: LocalDate get() = if (endDate.isBefore(date)) date else endDate

    val spanDays: Long get() = ChronoUnit.DAYS.between(date, lastDay).coerceAtLeast(0)

    fun covers(day: LocalDate): Boolean = occurrenceStartOn(day) != null

    fun occurrenceStartOn(day: LocalDate): LocalDate? {
        if (day.isBefore(this.date)) return null
        var candidate = day
        var step = 0L
        while (!candidate.isBefore(this.date) && step <= spanDays) {
            if (isOccurrenceStart(candidate) && !day.isAfter(candidate.plusDays(spanDays))) return candidate
            candidate = candidate.minusDays(1)
            step += 1
        }
        return null
    }

    fun isOccurrenceStart(start: LocalDate): Boolean {
        if (start.isBefore(date)) return false
        if (repeatUntil != null && start.isAfter(repeatUntil)) return false
        if (start in exceptDates) return false
        return when (repeat) {
            RepeatPeriod.NONE -> start == date
            RepeatPeriod.DAILY -> true
            RepeatPeriod.WEEKLY -> ChronoUnit.DAYS.between(date, start) % 7L == 0L
            RepeatPeriod.MONTHLY -> monthlyStart(start)
            RepeatPeriod.YEARLY -> yearlyStart(start)
        }
    }

    fun nextStartOnOrAfter(from: LocalDate): LocalDate? {
        var cursor = from
        repeat(480) {
            val candidate = alignedStart(cursor) ?: return null
            if (repeatUntil != null && candidate.isAfter(repeatUntil)) return null
            if (isOccurrenceStart(candidate)) return candidate
            val stepped = when (repeat) {
                RepeatPeriod.NONE -> return null
                RepeatPeriod.DAILY -> candidate.plusDays(1)
                RepeatPeriod.WEEKLY -> candidate.plusDays(7)
                RepeatPeriod.MONTHLY -> nextMonthly(candidate.plusDays(1)) ?: return null
                RepeatPeriod.YEARLY -> nextYearly(candidate.plusDays(1)) ?: return null
            }
            if (!stepped.isAfter(candidate)) return null
            cursor = stepped
        }
        return null
    }

    fun afterCut(on: LocalDate, cut: SeriesCut): Concert? {
        if (cut == SeriesCut.ALL || repeat == RepeatPeriod.NONE) return null
        val start = occurrenceStartOn(on) ?: return this
        return when (cut) {
            SeriesCut.ONLY_THIS -> copy(exceptDates = exceptDates + start)
            SeriesCut.FOLLOWING -> {
                if (!start.isAfter(date)) {
                    null
                } else {
                    val until = start.minusDays(1)
                    copy(
                        repeatUntil = until,
                        exceptDates = exceptDates.filter { !it.isAfter(until) }.toSet(),
                    )
                }
            }
            SeriesCut.PREVIOUS -> {
                val next = nextStartOnOrAfter(start.plusDays(1)) ?: return null
                copy(
                    date = next,
                    endDate = next.plusDays(spanDays),
                    exceptDates = exceptDates.filter { !it.isBefore(next) }.toSet(),
                )
            }
            SeriesCut.ALL -> null
        }
    }

    private fun alignedStart(from: LocalDate): LocalDate? {
        val begin = if (from.isBefore(date)) date else from
        if (repeatUntil != null && begin.isAfter(repeatUntil)) return null
        return when (repeat) {
            RepeatPeriod.NONE -> date.takeIf { !date.isBefore(from) }
            RepeatPeriod.DAILY -> begin
            RepeatPeriod.WEEKLY -> {
                val rem = ChronoUnit.DAYS.between(date, begin) % 7L
                if (rem == 0L) begin else begin.plusDays(7L - rem)
            }
            RepeatPeriod.MONTHLY -> nextMonthly(begin)
            RepeatPeriod.YEARLY -> nextYearly(begin)
        }
    }

    fun overlapsRange(rangeStart: LocalDate, rangeEnd: LocalDate): Boolean {
        if (rangeEnd.isBefore(rangeStart)) return false
        val start = nextStartOnOrAfter(rangeStart.minusDays(spanDays)) ?: return false
        return !start.isAfter(rangeEnd)
    }

    private fun monthlyStart(start: LocalDate): Boolean {
        if (start.dayOfMonth != date.dayOfMonth) return false
        val months = ChronoUnit.MONTHS.between(YearMonth.from(date), YearMonth.from(start))
        if (months < 0) return false
        val expected = runCatching { YearMonth.from(date).plusMonths(months).atDay(date.dayOfMonth) }.getOrNull()
        return expected == start
    }

    private fun yearlyStart(start: LocalDate): Boolean {
        if (start.month != date.month || start.dayOfMonth != date.dayOfMonth) return false
        val years = (start.year - date.year).toLong()
        if (years < 0) return false
        val expected = runCatching { date.withYear(start.year) }.getOrNull()
        return expected == start
    }

    private fun nextMonthly(from: LocalDate): LocalDate? {
        var month = YearMonth.from(from)
        repeat(48) {
            val candidate = runCatching { month.atDay(date.dayOfMonth) }.getOrNull()
            if (candidate != null && !candidate.isBefore(date) && !candidate.isBefore(from)) {
                if (repeatUntil == null || !candidate.isAfter(repeatUntil)) return candidate
                return null
            }
            month = month.plusMonths(1)
        }
        return null
    }

    private fun nextYearly(from: LocalDate): LocalDate? {
        var year = from.year
        repeat(12) {
            val candidate = runCatching { date.withYear(year) }.getOrNull()
            if (candidate != null && !candidate.isBefore(date) && !candidate.isBefore(from)) {
                if (repeatUntil == null || !candidate.isAfter(repeatUntil)) return candidate
                return null
            }
            year += 1
        }
        return null
    }
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
        concert.status != ConcertStatus.CANCELLED && concert.overlapsRange(start, end)
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

private val officialHolidays: Map<LocalDate, String> = mapOf(
    LocalDate.of(2027, 1, 1) to "Новогодние каникулы",
    LocalDate.of(2027, 1, 2) to "Новогодние каникулы",
    LocalDate.of(2027, 1, 3) to "Новогодние каникулы",
    LocalDate.of(2027, 1, 4) to "Новогодние каникулы",
    LocalDate.of(2027, 1, 5) to "Новогодние каникулы",
    LocalDate.of(2027, 1, 6) to "Новогодние каникулы",
    LocalDate.of(2027, 1, 7) to "Рождество",
    LocalDate.of(2027, 1, 8) to "Новогодние каникулы",
    LocalDate.of(2027, 2, 23) to "День защитника Отечества",
    LocalDate.of(2027, 3, 8) to "8 марта",
    LocalDate.of(2027, 5, 1) to "Праздник Весны и Труда",
    LocalDate.of(2027, 5, 9) to "День Победы",
    LocalDate.of(2027, 6, 12) to "День России",
    LocalDate.of(2027, 11, 4) to "День народного единства",
)

private val transferredDaysOff: Map<LocalDate, String> = mapOf(
    LocalDate.of(2026, 12, 31) to "Выходной",
    LocalDate.of(2027, 2, 22) to "Выходной",
    LocalDate.of(2027, 5, 3) to "Выходной",
    LocalDate.of(2027, 5, 10) to "Выходной",
    LocalDate.of(2027, 6, 14) to "Выходной",
    LocalDate.of(2027, 11, 5) to "Выходной",
    LocalDate.of(2027, 12, 31) to "Выходной",
)

private val shortWorkDays = setOf(
    LocalDate.of(2027, 2, 20),
    LocalDate.of(2027, 4, 30),
    LocalDate.of(2027, 6, 11),
    LocalDate.of(2027, 11, 3),
)

fun officialDay(date: LocalDate): OfficialDay? {
    officialHolidays[date]?.let { return OfficialDay(it, OfficialKind.HOLIDAY) }
    transferredDaysOff[date]?.let { return OfficialDay(it, OfficialKind.DAY_OFF) }
    if (date in shortWorkDays) return OfficialDay("Короткий день", OfficialKind.SHORT)
    if (date.year == 2027 && (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY)) {
        return OfficialDay("Выходной", OfficialKind.DAY_OFF)
    }
    return null
}

fun isOfficialDayOff(date: LocalDate): Boolean {
    val kind = officialDay(date)?.kind
    return kind == OfficialKind.HOLIDAY || kind == OfficialKind.DAY_OFF
}

fun daysIn(start: LocalDate, end: LocalDate): List<LocalDate> {
    if (end.isBefore(start)) return emptyList()
    return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
}

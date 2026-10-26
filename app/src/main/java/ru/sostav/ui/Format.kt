package ru.sostav.ui

import ru.sostav.logic.Availability
import ru.sostav.logic.ConcertStatus
import ru.sostav.logic.DayPart
import ru.sostav.logic.Rsvp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ru = Locale.forLanguageTag("ru")

val dayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", ru)
val fullDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", ru)
val monthTitle: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL yyyy", ru)
private val noticeTime: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", ru)

fun DayPart.ru(): String = when (this) {
    DayPart.MORNING -> "Утро"
    DayPart.AFTERNOON -> "День"
    DayPart.EVENING -> "Вечер"
}

fun DayPart.wire(): String = when (this) {
    DayPart.MORNING -> "morning"
    DayPart.AFTERNOON -> "afternoon"
    DayPart.EVENING -> "evening"
}

fun wirePart(value: String?): DayPart? = when (value) {
    "morning" -> DayPart.MORNING
    "afternoon" -> DayPart.AFTERNOON
    "evening" -> DayPart.EVENING
    else -> null
}

fun Availability.ru(): String = when (this) {
    Availability.CAN -> "Могу"
    Availability.CANNOT -> "Нет"
    Availability.MAYBE -> "Может"
}

fun Availability.wire(): String = when (this) {
    Availability.CAN -> "can"
    Availability.CANNOT -> "cannot"
    Availability.MAYBE -> "maybe"
}

fun wireAvailability(value: String?): Availability? = when (value) {
    "can" -> Availability.CAN
    "cannot" -> Availability.CANNOT
    "maybe" -> Availability.MAYBE
    else -> null
}

fun ConcertStatus.ru(): String = when (this) {
    ConcertStatus.IDEA -> "Идея"
    ConcertStatus.CONFIRMED -> "Будет"
    ConcertStatus.CANCELLED -> "Нет"
}

fun ConcertStatus.wire(): String = when (this) {
    ConcertStatus.IDEA -> "idea"
    ConcertStatus.CONFIRMED -> "confirmed"
    ConcertStatus.CANCELLED -> "cancelled"
}

fun wireConcertStatus(value: String?): ConcertStatus? = when (value) {
    "idea" -> ConcertStatus.IDEA
    "confirmed" -> ConcertStatus.CONFIRMED
    "cancelled" -> ConcertStatus.CANCELLED
    else -> null
}

fun Rsvp.ru(): String = when (this) {
    Rsvp.GOING -> "Еду"
    Rsvp.NOT_GOING -> "Не еду"
    Rsvp.MAYBE -> "Под вопросом"
}

fun Rsvp.wire(): String = when (this) {
    Rsvp.GOING -> "going"
    Rsvp.NOT_GOING -> "not_going"
    Rsvp.MAYBE -> "maybe"
}

fun wireRsvp(value: String?): Rsvp? = when (value) {
    "going" -> Rsvp.GOING
    "not_going" -> Rsvp.NOT_GOING
    "maybe" -> Rsvp.MAYBE
    else -> null
}

fun LocalDate.ruDay(): String = format(dayMonth)

fun LocalDate.ruFull(): String = format(fullDate)

fun LocalDate.ruRange(end: LocalDate): String =
    if (this == end) ruFull() else "${ruDay()} — ${end.ruFull()}"

fun millisToDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

fun dateToMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun formatNoticeTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(noticeTime)

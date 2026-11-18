package ru.sostav.logic

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class ImportedEvent(
    val uid: String,
    val title: String,
    val date: LocalDate,
    val endDate: LocalDate,
    val timeText: String,
    val hour: Int?,
    val location: String,
    val description: String,
    val cancelled: Boolean,
    val repeat: RepeatPeriod = RepeatPeriod.NONE,
    val repeatUntil: LocalDate? = null,
)

fun parseIcs(text: String, zone: ZoneId = ZoneId.of("Europe/Moscow")): List<ImportedEvent> {
    val lines = unfoldIcs(text)
    if (lines.none { it.equals("BEGIN:VCALENDAR", ignoreCase = true) }) {
        error("Это не файл календаря iCal")
    }
    return extractEventBlocks(lines)
        .flatMap { parseEvent(it, zone) }
        .filter { it.title.isNotBlank() }
        .distinctBy { "${it.uid}|${it.date}|${it.endDate}|${it.title}" }
        .sortedWith(compareBy({ it.date }, { it.timeText }, { it.title }))
}

fun alreadyImported(event: ImportedEvent, concerts: List<Concert>): Boolean {
    if (event.uid.isNotBlank() && concerts.any { it.importUid == event.uid }) return true
    return concerts.any {
        it.title.equals(event.title, ignoreCase = true) &&
            it.date == event.date &&
            it.lastDay == event.endDate
    }
}

fun toConcertDraft(event: ImportedEvent, createdBy: String): Concert {
    val (city, venue) = splitLocation(event.location)
    val note = listOf(event.timeText, event.description)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .take(499)
    return Concert(
        id = "",
        title = event.title.take(119).ifBlank { "Событие" },
        date = event.date,
        endDate = event.endDate,
        part = partFromHour(event.hour),
        city = city.take(80),
        venue = venue.take(80),
        note = note,
        status = if (event.cancelled) ConcertStatus.CANCELLED else ConcertStatus.CONFIRMED,
        createdBy = createdBy,
        importUid = event.uid,
        repeat = event.repeat,
        repeatUntil = event.repeatUntil,
    )
}

private data class IcsProp(val name: String, val params: Map<String, String>, val value: String)

private data class IcsWhen(
    val date: LocalDate,
    val time: LocalTime?,
)

private val dateStamp = DateTimeFormatter.ofPattern("yyyyMMdd")
private val dateTimeStamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
private val dateTimeMinute = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm")
private val weekDays = mapOf(
    "MO" to DayOfWeek.MONDAY,
    "TU" to DayOfWeek.TUESDAY,
    "WE" to DayOfWeek.WEDNESDAY,
    "TH" to DayOfWeek.THURSDAY,
    "FR" to DayOfWeek.FRIDAY,
    "SA" to DayOfWeek.SATURDAY,
    "SU" to DayOfWeek.SUNDAY,
)

private fun unfoldIcs(text: String): List<String> {
    val raw = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val lines = mutableListOf<String>()
    for (line in raw) {
        if (line.startsWith(" ") || line.startsWith("\t")) {
            if (lines.isNotEmpty()) lines[lines.lastIndex] = lines.last() + line.substring(1)
        } else if (line.isNotEmpty()) {
            lines += line
        }
    }
    return lines
}

private fun extractEventBlocks(lines: List<String>): List<List<String>> {
    val blocks = mutableListOf<List<String>>()
    var current: MutableList<String>? = null
    var nested = 0
    for (line in lines) {
        val upper = line.uppercase()
        if (current == null) {
            if (upper == "BEGIN:VEVENT") current = mutableListOf()
            continue
        }
        if (upper.startsWith("BEGIN:")) {
            nested += 1
            continue
        }
        if (nested > 0) {
            if (upper.startsWith("END:")) nested -= 1
            continue
        }
        if (upper == "END:VEVENT") {
            blocks += current
            current = null
            continue
        }
        current += line
    }
    return blocks
}

private fun parseEvent(lines: List<String>, zone: ZoneId): List<ImportedEvent> {
    val props = lines.mapNotNull(::parseProp)
    val title = unescape(props.lastOrNull { it.name == "SUMMARY" }?.value.orEmpty()).trim()
    if (title.isEmpty()) return emptyList()
    val startProp = props.lastOrNull { it.name == "DTSTART" } ?: return emptyList()
    val start = parseWhen(startProp, zone) ?: return emptyList()
    val end = props.lastOrNull { it.name == "DTEND" }?.let { parseWhen(it, zone) }
        ?: durationEnd(start, props.lastOrNull { it.name == "DURATION" }?.value)
    val allDay = start.time == null
    val last = lastDay(start, end, allDay)
    val timeText = formatTime(start, end, allDay)
    val hour = start.time?.hour
    val location = unescape(props.lastOrNull { it.name == "LOCATION" }?.value.orEmpty()).trim()
    val description = stripTags(unescape(props.lastOrNull { it.name == "DESCRIPTION" }?.value.orEmpty())).trim()
    val cancelled = props.lastOrNull { it.name == "STATUS" }?.value.equals("CANCELLED", true)
    val uid = unescape(props.lastOrNull { it.name == "UID" }?.value.orEmpty()).trim()
    val exception = props.any { it.name == "RECURRENCE-ID" }
    val rrule = props.lastOrNull { it.name == "RRULE" }?.value
    val exdates = props.filter { it.name == "EXDATE" }
        .flatMap { it.value.split(',') }
        .mapNotNull { raw -> parseWhen(IcsProp("EXDATE", emptyMap(), raw.trim()), zone)?.date }
        .toSet()
    val template = ImportedEvent(
        uid = instanceUid(uid, start.date),
        title = title,
        date = start.date,
        endDate = last,
        timeText = timeText,
        hour = hour,
        location = location,
        description = description,
        cancelled = cancelled,
    )
    if (exception || rrule.isNullOrBlank()) return listOf(template)
    collapseRrule(template, uid, rrule, exdates)?.let { return it }
    return expandRrule(start.date, last, rrule, exdates).map { (from, to) ->
        template.copy(uid = instanceUid(uid, from), date = from, endDate = to)
    }
}

private fun collapseRrule(
    template: ImportedEvent,
    uid: String,
    rule: String,
    exdates: Set<LocalDate>,
): List<ImportedEvent>? {
    if (exdates.isNotEmpty()) return null
    val parts = rule.split(';').mapNotNull { chunk ->
        val eq = chunk.indexOf('=')
        if (eq <= 0) null else chunk.substring(0, eq).uppercase() to chunk.substring(eq + 1)
    }.toMap()
    val freq = parts["FREQ"]?.uppercase() ?: return null
    val until = parts["UNTIL"]?.let { stamp ->
        runCatching { LocalDate.parse(stamp.take(8), dateStamp) }.getOrNull()
    } ?: template.date.plusMonths(12)
    val byDays = parts["BYDAY"].orEmpty().split(',').mapNotNull { token ->
        val clean = token.trim().uppercase()
        if (clean.length < 2) null else weekDays[clean.takeLast(2)]
    }
    val hasNth = parts["BYDAY"].orEmpty().split(',').any { it.trim().dropLast(2).toIntOrNull() != null }
    if (hasNth) return null
    val span = ChronoUnit.DAYS.between(template.date, template.endDate).coerceAtLeast(0)
    fun eventOn(start: LocalDate, period: RepeatPeriod): ImportedEvent = template.copy(
        uid = instanceUid(uid, start),
        date = start,
        endDate = start.plusDays(span),
        repeat = period,
        repeatUntil = until,
    )
    return when (freq) {
        "DAILY" -> listOf(eventOn(template.date, RepeatPeriod.DAILY))
        "WEEKLY" -> {
            val days = byDays.ifEmpty { listOf(template.date.dayOfWeek) }
            days.map { day ->
                var start = template.date
                while (start.dayOfWeek != day) start = start.plusDays(1)
                eventOn(start, RepeatPeriod.WEEKLY)
            }
        }
        "MONTHLY" -> if (parts["BYDAY"].isNullOrBlank()) {
            listOf(eventOn(template.date, RepeatPeriod.MONTHLY))
        } else {
            null
        }
        "YEARLY" -> listOf(eventOn(template.date, RepeatPeriod.YEARLY))
        else -> null
    }
}

private fun instanceUid(uid: String, date: LocalDate): String {
    val base = uid.ifBlank { "ics" }
    return "$base/$date"
}

private fun parseProp(line: String): IcsProp? {
    val colon = line.indexOf(':')
    if (colon <= 0) return null
    val meta = line.substring(0, colon)
    val value = line.substring(colon + 1)
    val parts = splitParams(meta)
    if (parts.isEmpty()) return null
    val params = mutableMapOf<String, String>()
    for (index in 1 until parts.size) {
        val item = parts[index]
        val eq = item.indexOf('=')
        if (eq <= 0) continue
        params[item.substring(0, eq).uppercase()] = item.substring(eq + 1).trim('"')
    }
    return IcsProp(parts[0].uppercase(), params, value)
}

private fun splitParams(meta: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    for (ch in meta) {
        when {
            ch == '"' -> {
                quoted = !quoted
                current.append(ch)
            }
            ch == ';' && !quoted -> {
                parts += current.toString()
                current.clear()
            }
            else -> current.append(ch)
        }
    }
    if (current.isNotEmpty()) parts += current.toString()
    return parts
}

private fun parseWhen(prop: IcsProp, zone: ZoneId): IcsWhen? {
    val raw = prop.value.trim()
    if (raw.isEmpty()) return null
    val asDate = prop.params["VALUE"].equals("DATE", true) || (!raw.contains('T') && raw.length >= 8)
    if (asDate) {
        val date = runCatching { LocalDate.parse(raw.take(8), dateStamp) }.getOrNull() ?: return null
        return IcsWhen(date, null)
    }
    val utc = raw.endsWith("Z", ignoreCase = true)
    val stamp = raw.removeSuffix("Z").removeSuffix("z")
    val local = runCatching {
        when {
            stamp.length >= 15 -> LocalDateTime.parse(stamp.take(15), dateTimeStamp)
            stamp.length >= 13 -> LocalDateTime.parse(stamp.take(13), dateTimeMinute)
            else -> null
        }
    }.getOrNull() ?: return null
    val usedZone = when {
        utc -> ZoneOffset.UTC
        else -> zoneOf(prop.params["TZID"], zone)
    }
    val zoned = local.atZone(usedZone).withZoneSameInstant(zone)
    return IcsWhen(zoned.toLocalDate(), zoned.toLocalTime())
}

private fun zoneOf(tzid: String?, fallback: ZoneId): ZoneId {
    val name = tzid?.trim()?.removePrefix("/")?.ifBlank { null } ?: return fallback
    return runCatching { ZoneId.of(name) }.getOrDefault(fallback)
}

private fun durationEnd(start: IcsWhen, value: String?): IcsWhen? {
    if (value.isNullOrBlank() || !value.startsWith("P")) return null
    val hours = Regex("""(\d+)H""").find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val minutes = Regex("""(\d+)M""").find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val days = Regex("""P(?:T.*)?(\d+)D""").find(value)?.groupValues?.get(1)?.toLongOrNull()
        ?: Regex("""^P(\d+)D""").find(value)?.groupValues?.get(1)?.toLongOrNull()
        ?: 0L
    if (start.time == null) return IcsWhen(start.date.plusDays(days), null)
    val end = LocalDateTime.of(start.date, start.time).plusDays(days).plusHours(hours).plusMinutes(minutes)
    return IcsWhen(end.toLocalDate(), end.toLocalTime())
}

private fun lastDay(start: IcsWhen, end: IcsWhen?, allDay: Boolean): LocalDate {
    if (end == null) return start.date
    if (allDay || end.time == null) {
        val last = end.date.minusDays(1)
        return if (last.isBefore(start.date)) start.date else last
    }
    if (end.time == LocalTime.MIDNIGHT && end.date.isAfter(start.date)) {
        return end.date.minusDays(1)
    }
    return if (end.date.isBefore(start.date)) start.date else end.date
}

private fun formatTime(start: IcsWhen, end: IcsWhen?, allDay: Boolean): String {
    val from = start.time ?: return ""
    if (allDay) return ""
    val startText = "%02d:%02d".format(from.hour, from.minute)
    val to = end?.time ?: return startText
    if (end.date != start.date && to == LocalTime.MIDNIGHT) return startText
    if (to == from) return startText
    return "$startText–%02d:%02d".format(to.hour, to.minute)
}

private fun expandRrule(
    start: LocalDate,
    last: LocalDate,
    rule: String,
    exdates: Set<LocalDate>,
): List<Pair<LocalDate, LocalDate>> {
    val parts = rule.split(';').mapNotNull { chunk ->
        val eq = chunk.indexOf('=')
        if (eq <= 0) null else chunk.substring(0, eq).uppercase() to chunk.substring(eq + 1)
    }.toMap()
    val freq = parts["FREQ"]?.uppercase() ?: return listOf(start to last)
    val interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val count = parts["COUNT"]?.toIntOrNull()?.coerceIn(1, 80) ?: 80
    val until = parts["UNTIL"]?.let { stamp ->
        runCatching { LocalDate.parse(stamp.take(8), dateStamp) }.getOrNull()
    } ?: start.plusMonths(12)
    val span = ChronoUnit.DAYS.between(start, last).coerceAtLeast(0)
    val byDays = parts["BYDAY"].orEmpty().split(',').mapNotNull { token ->
        val clean = token.trim().uppercase()
        if (clean.length < 2) null else weekDays[clean.takeLast(2)]
    }
    val monthlyWeekday = parts["BYDAY"].orEmpty().split(',').firstNotNullOfOrNull { token ->
        val clean = token.trim().uppercase()
        val day = weekDays[clean.takeLast(2)] ?: return@firstNotNullOfOrNull null
        val ordinal = clean.dropLast(2).toIntOrNull() ?: return@firstNotNullOfOrNull null
        day to ordinal
    }
    val monthDay = parts["BYMONTHDAY"]?.toIntOrNull()
    val dates = when (freq) {
        "DAILY" -> generateSequence(start) { it.plusDays(interval.toLong()) }
            .takeWhile { !it.isAfter(until) }
            .take(count)
            .toList()
        "WEEKLY" -> expandWeekly(start, until, interval, count, byDays)
        "MONTHLY" -> expandMonthly(start, until, interval, count, monthDay, monthlyWeekday)
        else -> listOf(start)
    }
    return dates
        .filterNot { it in exdates }
        .map { day -> day to day.plusDays(span) }
}

private fun expandWeekly(
    start: LocalDate,
    until: LocalDate,
    interval: Int,
    count: Int,
    byDays: List<DayOfWeek>,
): List<LocalDate> {
    val days = byDays.ifEmpty { listOf(start.dayOfWeek) }
    val originMonday = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val out = mutableListOf<LocalDate>()
    var cursor = start
    while (!cursor.isAfter(until) && out.size < count) {
        val weekMonday = cursor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeks = ChronoUnit.WEEKS.between(originMonday, weekMonday)
        if (weeks % interval == 0L && cursor.dayOfWeek in days) out += cursor
        cursor = cursor.plusDays(1)
    }
    return out
}

private fun expandMonthly(
    start: LocalDate,
    until: LocalDate,
    interval: Int,
    count: Int,
    monthDay: Int?,
    weekday: Pair<DayOfWeek, Int>?,
): List<LocalDate> {
    val out = mutableListOf<LocalDate>()
    var month = java.time.YearMonth.from(start)
    while (out.size < count) {
        val date = when {
            weekday != null -> nthWeekday(month, weekday.second, weekday.first)
            monthDay != null -> runCatching { month.atDay(monthDay) }.getOrNull()
            else -> runCatching { month.atDay(start.dayOfMonth) }.getOrNull()
        }
        if (date != null && !date.isBefore(start) && !date.isAfter(until)) out += date
        month = month.plusMonths(interval.toLong())
        if (month.atDay(1).isAfter(until)) break
        if (month.isAfter(java.time.YearMonth.from(until).plusMonths(1))) break
    }
    return out
}

private fun nthWeekday(month: java.time.YearMonth, ordinal: Int, day: DayOfWeek): LocalDate? {
    if (ordinal > 0) {
        val first = month.atDay(1).with(TemporalAdjusters.nextOrSame(day))
        val date = first.plusWeeks((ordinal - 1).toLong())
        return date.takeIf { java.time.YearMonth.from(date) == month }
    }
    if (ordinal < 0) {
        val last = month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(day))
        val date = last.plusWeeks((ordinal + 1).toLong())
        return date.takeIf { java.time.YearMonth.from(date) == month }
    }
    return null
}

private fun unescape(value: String): String = buildString(value.length) {
    var index = 0
    while (index < value.length) {
        val ch = value[index]
        if (ch == '\\' && index + 1 < value.length) {
            when (val next = value[index + 1]) {
                'n', 'N' -> append('\n')
                ',', ';', '\\' -> append(next)
                else -> append(next)
            }
            index += 2
        } else {
            append(ch)
            index += 1
        }
    }
}

private fun stripTags(value: String): String {
    if (!value.contains('<')) return value.replace("\\n", "\n")
    return value.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
}

private fun splitLocation(location: String): Pair<String, String> {
    val clean = location.trim()
    if (clean.isEmpty()) return "" to ""
    val comma = clean.indexOf(',')
    if (comma in 1..39) {
        val city = clean.substring(0, comma).trim()
        val venue = clean.substring(comma + 1).trim()
        if (city.isNotBlank() && venue.isNotBlank()) return city to venue
    }
    return "" to clean
}

private fun partFromHour(hour: Int?): DayPart = when {
    hour == null -> DayPart.EVENING
    hour < 12 -> DayPart.MORNING
    hour < 18 -> DayPart.AFTERNOON
    else -> DayPart.EVENING
}

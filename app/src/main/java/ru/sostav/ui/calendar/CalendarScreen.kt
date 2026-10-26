package ru.sostav.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sostav.logic.Availability
import ru.sostav.logic.absenceLabel
import ru.sostav.logic.concertsOn
import ru.sostav.logic.exactDay
import ru.sostav.logic.monthGrid
import ru.sostav.logic.winningAbsence
import ru.sostav.ui.GroupDataViewModel
import ru.sostav.ui.components.ChoiceButtons
import ru.sostav.ui.components.StripeRow
import ru.sostav.ui.components.availabilityDot
import ru.sostav.ui.components.concertColor
import ru.sostav.ui.components.concertOnColor
import ru.sostav.ui.monthTitle
import ru.sostav.ui.ru
import ru.sostav.ui.ruFull
import ru.sostav.ui.ruRange
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

private val weekdays = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

private data class DayChip(
    val id: String,
    val text: String,
    val event: Boolean,
    val status: ru.sostav.logic.ConcertStatus? = null,
    val absenceStatus: Availability? = null,
)

@Composable
fun CalendarScreen(
    data: GroupDataViewModel,
    onOpenConcert: (String) -> Unit,
    onCreateConcert: (LocalDate) -> Unit,
    onCreateAbsence: (LocalDate) -> Unit,
    onEditAbsence: (String) -> Unit,
    onInbox: () -> Unit,
) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var selectedText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val month = YearMonth.parse(monthText)
    val selected = runCatching { LocalDate.parse(selectedText) }.getOrDefault(LocalDate.now())
    val today = LocalDate.now()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 96.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
                Text(
                    data.group?.name?.ifBlank { null } ?: "Календарь",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = {
                    monthText = YearMonth.now().toString()
                    selectedText = today.toString()
                }) { Text("Сегодня") }
                IconButton(onClick = onInbox) {
                    BadgedBox(badge = {
                        if (data.unread > 0) Badge { Text(data.unread.coerceAtMost(9).toString()) }
                    }) {
                        Icon(Icons.Filled.Notifications, contentDescription = "Уведомления")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                IconButton(onClick = {
                    val (nextMonth, nextDay) = shiftMonth(month, selected, -1)
                    monthText = nextMonth
                    selectedText = nextDay
                }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Предыдущий месяц")
                }
                Text(
                    month.atDay(1).format(monthTitle).replaceFirstChar { it.titlecase(Locale.forLanguageTag("ru")) },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = {
                    val (nextMonth, nextDay) = shiftMonth(month, selected, 1)
                    monthText = nextMonth
                    selectedText = nextDay
                }) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Следующий месяц")
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                weekdays.forEach { day ->
                    Text(
                        day,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            monthGrid(month).chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    week.forEach { day ->
                        Box(Modifier.weight(1f).height(88.dp)) {
                            if (day != null) {
                                val chips = chipsOn(data, day)
                                DayCell(
                                    day = day,
                                    today = today,
                                    selected = day == selected,
                                    chips = chips,
                                    onClick = {
                                        selectedText = day.toString()
                                        if (YearMonth.from(day).toString() != monthText) {
                                            monthText = YearMonth.from(day).toString()
                                        }
                                    },
                                    onChip = { chip ->
                                        if (chip.event) {
                                            onOpenConcert(chip.id)
                                        } else {
                                            val mine = data.absences.find { it.id == chip.id && it.uid == data.uid }
                                            if (mine != null) onEditAbsence(chip.id)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            DayAgenda(
                data = data,
                date = selected,
                onOpenConcert = onOpenConcert,
                onEditAbsence = onEditAbsence,
                onCreateAbsence = onCreateAbsence,
            )
        }
        FloatingActionButton(
            onClick = { onCreateConcert(selected) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Новое событие")
        }
    }
}

private fun shiftMonth(month: YearMonth, selected: LocalDate, delta: Long): Pair<String, String> {
    val next = month.plusMonths(delta)
    val day = selected.dayOfMonth.coerceAtMost(next.lengthOfMonth())
    return next.toString() to next.atDay(day).toString()
}

private fun chipsOn(data: GroupDataViewModel, day: LocalDate): List<DayChip> {
    val events = concertsOn(data.concerts, day).map { concert ->
        DayChip(concert.id, concert.title, event = true, status = concert.status)
    }
    val marks = data.absences
        .filter { it.covers(day) }
        .map { absence ->
            val name = data.members.find { it.uid == absence.uid }?.displayName ?: "Участник"
            DayChip(absence.id, absenceLabel(absence, name), event = false, absenceStatus = absence.status)
        }
        .distinctBy { it.text.lowercase() }
    return events + marks
}

@Composable
private fun DayCell(
    day: LocalDate,
    today: LocalDate,
    selected: Boolean,
    chips: List<DayChip>,
    onClick: () -> Unit,
    onChip: (DayChip) -> Unit,
) {
    val description = buildString {
        append(day.ruFull())
        if (day == today) append(", сегодня")
        chips.forEach { append(", ${it.text}") }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(if (day == today) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    day.dayOfMonth.toString(),
                    color = when {
                        day == today -> MaterialTheme.colorScheme.onPrimary
                        selected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (day == today || selected) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        val visible = chips.take(3)
        visible.forEach { chip ->
            val background = if (chip.event && chip.status != null) {
                concertColor(chip.status)
            } else {
                markColor(chip.absenceStatus)
            }
            val foreground = if (chip.event && chip.status != null) {
                concertOnColor(chip.status)
            } else {
                markOnColor(chip.absenceStatus)
            }
            Text(
                chip.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(3.dp))
                    .background(background)
                    .clickable { onChip(chip) }
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 10.sp,
                lineHeight = 12.sp,
            )
        }
        if (chips.size > visible.size) {
            Text(
                "+${chips.size - visible.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

@Composable
private fun markColor(status: Availability?): Color {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    return when (status) {
        Availability.CANNOT -> if (dark) Color(0xFF7F1D1D) else Color(0xFFFECACA)
        Availability.MAYBE -> if (dark) Color(0xFF78350F) else Color(0xFFFEF3C7)
        Availability.CAN -> if (dark) Color(0xFF064E3B) else Color(0xFFD1FAE5)
        null -> MaterialTheme.colorScheme.surfaceVariant
    }
}

@Composable
private fun markOnColor(status: Availability?): Color {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    return when (status) {
        Availability.CANNOT -> if (dark) Color(0xFFFECACA) else Color(0xFF991B1B)
        Availability.MAYBE -> if (dark) Color(0xFFFDE68A) else Color(0xFF92400E)
        Availability.CAN -> if (dark) Color(0xFFA7F3D0) else Color(0xFF065F46)
        null -> MaterialTheme.colorScheme.onSurface
    }
}

@Composable
private fun DayAgenda(
    data: GroupDataViewModel,
    date: LocalDate,
    onOpenConcert: (String) -> Unit,
    onEditAbsence: (String) -> Unit,
    onCreateAbsence: (LocalDate) -> Unit,
) {
    val collator = java.text.Collator.getInstance(Locale.forLanguageTag("ru"))
    val dayConcerts = concertsOn(data.concerts, date).sortedBy { it.date }
    val dayMarks = data.absences.filter { it.covers(date) }.sortedBy { it.start }
    val people = data.members.sortedWith { left, right ->
        fun rank(uid: String) = when (winningAbsence(data.absences, uid, date)?.status) {
            Availability.CANNOT -> 0
            Availability.MAYBE -> 1
            null -> 2
            Availability.CAN -> 3
        }
        val byStatus = rank(left.uid).compareTo(rank(right.uid))
        if (byStatus != 0) byStatus else collator.compare(left.displayName, right.displayName)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            date.ruFull(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (dayConcerts.isEmpty() && dayMarks.isEmpty()) {
            Text(
                "В этот день пока пусто",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        dayConcerts.forEach { concert ->
            val place = listOf(concert.city, concert.venue).filter { it.isNotBlank() }.joinToString(", ")
            val whenText = concert.date.ruRange(concert.lastDay)
            StripeRow(
                title = concert.title,
                subtitle = listOf(whenText, concert.status.ru(), place, concert.note).filter { it.isNotBlank() }.joinToString(" · "),
                stripe = concertColor(concert.status),
                onClick = { onOpenConcert(concert.id) },
            )
        }
        dayMarks.forEach { absence ->
            val name = data.members.find { it.uid == absence.uid }?.displayName ?: "Участник"
            StripeRow(
                title = absenceLabel(absence, name),
                subtitle = listOf(absence.start.ruRange(absence.end), absence.status.ru()).joinToString(" · "),
                stripe = markColor(absence.status),
                onClick = { if (absence.uid == data.uid) onEditAbsence(absence.id) },
            )
        }
        Text(
            "Состав",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
        )
        if (people.isEmpty()) {
            Text(
                "В группе пока никого, кроме вас",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        people.forEach { member ->
            val winning = winningAbsence(data.absences, member.uid, date)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(availabilityDot(winning?.status), CircleShape),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        if (member.uid == data.uid) "${member.displayName} (вы)" else member.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        winning?.let { absenceLabel(it, member.displayName) } ?: "Нет отметки",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val exact = exactDay(data.absences, data.uid, date)
        val winningMine = winningAbsence(data.absences, data.uid, date)
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (winningMine != null && exact == null) {
                Text(
                    "На эти дни уже стоит «${absenceLabel(winningMine, data.myName)}» с ${winningMine.start.ruRange(winningMine.end)}.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(
                    onClick = { onEditAbsence(winningMine.id) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Изменить") }
            } else {
                Text("Отметить этот день", style = MaterialTheme.typography.titleSmall)
                ChoiceButtons(
                    options = listOf(
                        "Могу" to (exact?.status == Availability.CAN),
                        "Нет" to (exact?.status == Availability.CANNOT),
                        "Может" to (exact?.status == Availability.MAYBE),
                    ),
                    onSelect = { index ->
                        val status = listOf(Availability.CAN, Availability.CANNOT, Availability.MAYBE)[index]
                        data.saveQuick(date, status)
                    },
                )
            }
            OutlinedButton(
                onClick = { onCreateAbsence(date) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Несколько дней") }
        }
    }
}

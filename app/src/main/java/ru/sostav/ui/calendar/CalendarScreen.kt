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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.sostav.logic.Availability
import ru.sostav.logic.ConcertStatus
import ru.sostav.logic.DayPart
import ru.sostav.logic.concertsOn
import ru.sostav.logic.exactSlot
import ru.sostav.logic.monthGrid
import ru.sostav.logic.winningAbsence
import ru.sostav.ui.GroupDataViewModel
import ru.sostav.ui.components.ChoiceButtons
import ru.sostav.ui.components.InboxButton
import ru.sostav.ui.components.StatusChip
import ru.sostav.ui.monthTitle
import ru.sostav.ui.ru
import ru.sostav.ui.ruFull
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

private val weekdays = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    data: GroupDataViewModel,
    onOpenConcert: (String) -> Unit,
    onCreateConcert: (LocalDate, DayPart) -> Unit,
    onEditAbsence: (String) -> Unit,
    onInbox: () -> Unit,
) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var sheetDay by rememberSaveable { mutableStateOf<String?>(null) }
    val month = YearMonth.parse(monthText)
    val today = LocalDate.now()

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { monthText = month.minusMonths(1).toString() }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Предыдущий месяц")
            }
            Text(
                month.atDay(1).format(monthTitle),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = { monthText = month.plusMonths(1).toString() }) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Следующий месяц")
            }
            InboxButton(data.unread, onInbox)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            weekdays.forEach { day ->
                Text(
                    day,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        monthGrid(month).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    Box(Modifier.weight(1f).height(56.dp), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            DayCell(
                                day = day,
                                today = today,
                                selected = sheetDay == day.toString(),
                                cannot = data.members.count { member ->
                                    winningAbsence(data.absences, member.uid, day, DayPart.MORNING)?.status == Availability.CANNOT ||
                                        winningAbsence(data.absences, member.uid, day, DayPart.AFTERNOON)?.status == Availability.CANNOT ||
                                        winningAbsence(data.absences, member.uid, day, DayPart.EVENING)?.status == Availability.CANNOT
                                },
                                hasConcert = data.concerts.any { it.date == day && it.status != ConcertStatus.CANCELLED },
                                onClick = { sheetDay = day.toString() },
                            )
                        }
                    }
                }
            }
        }
        Text(
            "Число — сколько человек не может хотя бы в одну часть дня. Буква К — есть концерт.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
        )
    }

    val selected = sheetDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (selected != null) {
        ModalBottomSheet(
            onDismissRequest = { sheetDay = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            DaySheet(
                data = data,
                date = selected,
                onOpenConcert = {
                    sheetDay = null
                    onOpenConcert(it)
                },
                onCreateConcert = { part ->
                    sheetDay = null
                    onCreateConcert(selected, part)
                },
                onEditAbsence = {
                    sheetDay = null
                    onEditAbsence(it)
                },
            )
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    today: LocalDate,
    selected: Boolean,
    cannot: Int,
    hasConcert: Boolean,
    onClick: () -> Unit,
) {
    val description = buildString {
        append(day.ruFull())
        if (day == today) append(", сегодня")
        if (cannot > 0) append(", не могут: $cannot")
        if (hasConcert) append(", есть концерт")
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            day.dayOfMonth.toString(),
            fontWeight = if (day == today || selected) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (cannot > 0) {
                Text(
                    cannot.toString(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (hasConcert) {
                Text("К", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun DaySheet(
    data: GroupDataViewModel,
    date: LocalDate,
    onOpenConcert: (String) -> Unit,
    onCreateConcert: (DayPart) -> Unit,
    onEditAbsence: (String) -> Unit,
) {
    var partName by rememberSaveable { mutableStateOf(DayPart.EVENING.name) }
    val part = DayPart.valueOf(partName)
    val collator = java.text.Collator.getInstance(Locale.forLanguageTag("ru"))
    val people = data.members.sortedWith { left, right ->
        fun rank(uid: String) = when (winningAbsence(data.absences, uid, date, part)?.status) {
            Availability.CANNOT -> 0
            Availability.MAYBE -> 1
            null -> 2
            Availability.CAN -> 3
        }
        val byStatus = rank(left.uid).compareTo(rank(right.uid))
        if (byStatus != 0) byStatus else collator.compare(left.displayName, right.displayName)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(date.ruFull(), style = MaterialTheme.typography.titleLarge)
        ChoiceButtons(
            options = DayPart.entries.map { it.ru() to (it == part) },
            onSelect = { partName = DayPart.entries[it].name },
        )
        val dayConcerts = concertsOn(data.concerts, date, part)
        if (dayConcerts.isEmpty()) {
            Text("Концертов в эту часть дня нет", style = MaterialTheme.typography.bodyMedium)
        } else {
            dayConcerts.forEach { concert ->
                OutlinedButton(
                    onClick = { onOpenConcert(concert.id) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("${concert.title}. ${concert.status.ru()}")
                }
            }
        }
        people.forEach { member ->
            val winning = winningAbsence(data.absences, member.uid, date, part)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (member.uid == data.uid) "${member.displayName} (вы)" else member.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (!winning?.note.isNullOrBlank()) {
                        Text(winning?.note.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                StatusChip(winning?.status)
            }
        }
        val exact = exactSlot(data.absences, data.uid, date, part)
        val winningMine = winningAbsence(data.absences, data.uid, date, part)
        if (winningMine != null && exact == null) {
            Text(
                "На этот день уже есть период «${winningMine.status.ru()}» с ${winningMine.start.ruFull()} по ${winningMine.end.ruFull()}. Точечная отметка его не перебьёт.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                onClick = { onEditAbsence(winningMine.id) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Изменить период") }
        } else {
            Text("Ваша отметка на ${part.ru().lowercase()}", style = MaterialTheme.typography.titleSmall)
            ChoiceButtons(
                options = listOf(
                    "Могу" to (exact?.status == Availability.CAN),
                    "Не могу" to (exact?.status == Availability.CANNOT),
                    "Может быть" to (exact?.status == Availability.MAYBE),
                ),
                onSelect = { index ->
                    val status = listOf(Availability.CAN, Availability.CANNOT, Availability.MAYBE)[index]
                    data.saveQuick(date, part, status)
                },
            )
        }
        OutlinedButton(
            onClick = { onCreateConcert(part) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Концерт в этот день") }
        Box(Modifier.height(24.dp))
    }
}

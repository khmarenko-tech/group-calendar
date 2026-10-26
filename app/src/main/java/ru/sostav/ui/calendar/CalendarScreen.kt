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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import ru.sostav.logic.Concert
import ru.sostav.logic.ConcertStatus
import ru.sostav.logic.DayPart
import ru.sostav.logic.exactSlot
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
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

private val weekdays = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

@Composable
fun CalendarScreen(
    data: GroupDataViewModel,
    onOpenConcert: (String) -> Unit,
    onCreateConcert: (LocalDate, DayPart) -> Unit,
    onEditAbsence: (String) -> Unit,
    onInbox: () -> Unit,
) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var selectedText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var partName by rememberSaveable { mutableStateOf(DayPart.EVENING.name) }
    val month = YearMonth.parse(monthText)
    val selected = runCatching { LocalDate.parse(selectedText) }.getOrDefault(LocalDate.now())
    val part = DayPart.valueOf(partName)
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
                        Box(Modifier.weight(1f).height(62.dp)) {
                            if (day != null) {
                                val dayConcerts = data.concerts.filter { it.date == day }.sortedBy { it.part.ordinal }
                                val cannot = data.members.count { member ->
                                    DayPart.entries.any { slot ->
                                        winningAbsence(data.absences, member.uid, day, slot)?.status == Availability.CANNOT
                                    }
                                }
                                DayCell(
                                    day = day,
                                    today = today,
                                    selected = day == selected,
                                    concerts = dayConcerts,
                                    cannot = cannot,
                                    onClick = {
                                        selectedText = day.toString()
                                        if (YearMonth.from(day).toString() != monthText) monthText = YearMonth.from(day).toString()
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
                part = part,
                onPart = { partName = it.name },
                onOpenConcert = onOpenConcert,
                onEditAbsence = onEditAbsence,
            )
        }
        FloatingActionButton(
            onClick = { onCreateConcert(selected, part) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Новый концерт")
        }
    }
}

private fun shiftMonth(month: YearMonth, selected: LocalDate, delta: Long): Pair<String, String> {
    val next = month.plusMonths(delta)
    val day = selected.dayOfMonth.coerceAtMost(next.lengthOfMonth())
    return next.toString() to next.atDay(day).toString()
}

@Composable
private fun DayCell(
    day: LocalDate,
    today: LocalDate,
    selected: Boolean,
    concerts: List<Concert>,
    cannot: Int,
    onClick: () -> Unit,
) {
    val description = buildString {
        append(day.ruFull())
        if (day == today) append(", сегодня")
        concerts.forEach { append(", ${it.title}") }
        if (cannot > 0) append(", не могут: $cannot")
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
            if (cannot > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 2.dp)
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                )
            }
        }
        val visible = concerts.take(2)
        visible.forEach { concert ->
            Text(
                concert.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(3.dp))
                    .background(concertColor(concert.status))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                color = concertOnColor(concert.status),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 10.sp,
                lineHeight = 12.sp,
            )
        }
        if (concerts.size > visible.size) {
            Text(
                "+${concerts.size - visible.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

@Composable
private fun DayAgenda(
    data: GroupDataViewModel,
    date: LocalDate,
    part: DayPart,
    onPart: (DayPart) -> Unit,
    onOpenConcert: (String) -> Unit,
    onEditAbsence: (String) -> Unit,
) {
    val collator = java.text.Collator.getInstance(Locale.forLanguageTag("ru"))
    val dayConcerts = data.concerts.filter { it.date == date }.sortedBy { it.part.ordinal }
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
        Box(Modifier.padding(horizontal = 16.dp)) {
            ChoiceButtons(
                options = DayPart.entries.map { it.ru() to (it == part) },
                onSelect = { onPart(DayPart.entries[it]) },
            )
        }
        if (dayConcerts.isEmpty()) {
            Text(
                "Концертов в этот день нет",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } else {
            dayConcerts.forEach { concert ->
                val place = listOf(concert.city, concert.venue).filter { it.isNotBlank() }.joinToString(", ")
                StripeRow(
                    title = concert.title,
                    subtitle = listOf(concert.part.ru(), concert.status.ru(), place).filter { it.isNotBlank() }.joinToString(" · "),
                    stripe = concertColor(concert.status),
                    onClick = { onOpenConcert(concert.id) },
                )
            }
        }
        Text(
            "Состав, ${part.ru().lowercase()}",
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
            val winning = winningAbsence(data.absences, member.uid, date, part)
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
                        winning?.status?.ru() ?: "Нет отметки",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val note = winning?.note.orEmpty()
                    if (note.isNotBlank()) {
                        Text(note, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        val exact = exactSlot(data.absences, data.uid, date, part)
        val winningMine = winningAbsence(data.absences, data.uid, date, part)
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                Text("Ваша отметка", style = MaterialTheme.typography.titleSmall)
                Box(Modifier.padding(end = 72.dp)) {
                    ChoiceButtons(
                        options = listOf(
                            "Могу" to (exact?.status == Availability.CAN),
                            "Не могу" to (exact?.status == Availability.CANNOT),
                            "Под вопросом" to (exact?.status == Availability.MAYBE),
                        ),
                        onSelect = { index ->
                            val status = listOf(Availability.CAN, Availability.CANNOT, Availability.MAYBE)[index]
                            data.saveQuick(date, part, status)
                        },
                    )
                }
            }
        }
    }
}

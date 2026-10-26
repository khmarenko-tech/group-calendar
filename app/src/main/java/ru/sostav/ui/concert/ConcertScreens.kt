package ru.sostav.ui.concert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import ru.sostav.data.Graph
import ru.sostav.data.MemberRole
import ru.sostav.data.userMessage
import ru.sostav.logic.Availability
import ru.sostav.logic.Concert
import ru.sostav.logic.ConcertStatus
import ru.sostav.logic.DayPart
import ru.sostav.logic.Rsvp
import ru.sostav.logic.blockingAbsences
import ru.sostav.logic.daysIn
import ru.sostav.logic.winningAbsence
import ru.sostav.ui.GroupDataViewModel
import ru.sostav.ui.components.ChoiceButtons
import ru.sostav.ui.components.ConcertChip
import ru.sostav.ui.components.ConfirmDialog
import ru.sostav.ui.components.DateField
import ru.sostav.ui.components.EmptyText
import ru.sostav.ui.components.InboxButton
import ru.sostav.ui.components.StripeRow
import ru.sostav.ui.components.concertColor
import ru.sostav.ui.ru
import ru.sostav.ui.ruRange
import java.time.LocalDate

@Composable
fun ConcertListScreen(
    data: GroupDataViewModel,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onInbox: () -> Unit,
) {
    val today = LocalDate.now()
    val upcoming = data.concerts.filter { !it.lastDay.isBefore(today) }.sortedBy { it.date }
    val past = data.concerts.filter { it.lastDay.isBefore(today) }.sortedByDescending { it.date }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("События", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                InboxButton(data.unread, onInbox)
            }
            if (data.concerts.isEmpty()) EmptyText("Пока нет событий. Добавьте дату, когда она появится.")
            if (upcoming.isNotEmpty()) Text("Дальше", style = MaterialTheme.typography.titleMedium)
            upcoming.forEach { ConcertRow(it, data, onOpen) }
            if (past.isNotEmpty()) Text("Прошедшие", style = MaterialTheme.typography.titleMedium)
            past.forEach { ConcertRow(it, data, onOpen) }
            Box(Modifier.heightIn(min = 88.dp))
        }
        ExtendedFloatingActionButton(
            onClick = onCreate,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Событие") },
        )
    }
}

@Composable
private fun ConcertRow(concert: Concert, data: GroupDataViewModel, onOpen: (String) -> Unit) {
    val memberIds = data.members.map { it.uid }.toSet()
    val blocked = daysIn(concert.date, concert.lastDay)
        .flatMap { day -> blockingAbsences(data.absences, day) }
        .distinctBy { it.uid }
        .count { it.uid in memberIds }
    val place = listOf(concert.city, concert.venue).filter { it.isNotBlank() }.joinToString(", ")
    val whenText = concert.date.ruRange(concert.lastDay)
    val blockedText = if (blocked > 0 && concert.status != ConcertStatus.CANCELLED) "Не могут: $blocked" else ""
    StripeRow(
        title = concert.title,
        subtitle = listOf(whenText, place, concert.note, blockedText).filter { it.isNotBlank() }.joinToString(" · "),
        stripe = concertColor(concert.status),
        onClick = { onOpen(concert.id) },
        trailing = { ConcertChip(concert.status) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConcertDetailScreen(
    data: GroupDataViewModel,
    concertId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val concert = data.concerts.find { it.id == concertId }
    val rsvps by remember(concertId, data.groupId) {
        Graph.schedule.rsvps(data.groupId, concertId)
    }.collectAsState(initial = emptyList())
    var confirmDelete by remember { mutableStateOf(false) }
    val canEdit = concert != null && (concert.createdBy == data.uid || data.me?.role == MemberRole.ADMIN)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(concert?.title ?: "Событие") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Назад") }
            },
        )
        if (concert == null) {
            Text("Событие уже удалено", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            Button(
                onClick = onBack,
                modifier = Modifier.padding(16.dp).fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Назад") }
            return
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConcertChip(concert.status)
            Text(concert.date.ruRange(concert.lastDay), style = MaterialTheme.typography.titleMedium)
            val place = listOf(concert.city, concert.venue).filter { it.isNotBlank() }.joinToString(", ")
            if (place.isNotBlank()) Text(place, style = MaterialTheme.typography.bodyLarge)
            if (concert.note.isNotBlank()) Text(concert.note, style = MaterialTheme.typography.bodyLarge)
            if (concert.status == ConcertStatus.CANCELLED) {
                Text("Событие отменено", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
            Text("Вы будете?", style = MaterialTheme.typography.titleSmall)
            val mine = rsvps.find { it.uid == data.uid }?.status
            ChoiceButtons(
                options = listOf(
                    "Да" to (mine == Rsvp.GOING),
                    "Нет" to (mine == Rsvp.NOT_GOING),
                    "Может" to (mine == Rsvp.MAYBE),
                ),
                onSelect = { index ->
                    data.setRsvp(concert.id, listOf(Rsvp.GOING, Rsvp.NOT_GOING, Rsvp.MAYBE)[index])
                },
            )
            val memberIds = data.members.map { it.uid }.toSet()
            val visibleRsvps = rsvps.filter { it.uid in memberIds }
            RsvpBlock("Будут", data, concert, visibleRsvps.filter { it.status == Rsvp.GOING }.map { it.uid })
            RsvpBlock("Не будут", data, concert, visibleRsvps.filter { it.status == Rsvp.NOT_GOING }.map { it.uid })
            RsvpBlock("Под вопросом", data, concert, visibleRsvps.filter { it.status == Rsvp.MAYBE }.map { it.uid })
            val answered = visibleRsvps.map { it.uid }.toSet()
            RsvpBlock("Не ответили", data, concert, data.members.map { it.uid }.filter { it !in answered })
            if (canEdit) {
                Button(
                    onClick = { onEdit(concert.id) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Изменить") }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Удалить") }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Удалить событие?",
            body = "Ответы тоже пропадут.",
            confirm = "Удалить",
            onConfirm = {
                confirmDelete = false
                onBack()
                data.deleteConcert(concertId)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun RsvpBlock(
    title: String,
    data: GroupDataViewModel,
    concert: Concert,
    uids: List<String>,
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    if (uids.isEmpty()) {
        Text("Никого", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    uids.forEach { uid ->
        val member = data.members.find { it.uid == uid }
        val calendar = winningAbsence(data.absences, uid, concert.date)?.status
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(member?.displayName ?: "Участник", style = MaterialTheme.typography.bodyLarge)
            if (calendar == Availability.CANNOT) {
                Text("В календаре: не могу", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            } else if (calendar == Availability.MAYBE) {
                Text("В календаре: под вопросом", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

class ConcertFormViewModel : ViewModel() {
    var title by mutableStateOf("")
    var date by mutableStateOf(LocalDate.now())
    var endDate by mutableStateOf(LocalDate.now())
    var city by mutableStateOf("")
    var venue by mutableStateOf("")
    var note by mutableStateOf("")
    var status by mutableStateOf(ConcertStatus.IDEA)
    private var boundId: String? = null

    fun load(concertId: String?, concert: Concert?, dateText: String) {
        if (!concertId.isNullOrBlank()) {
            if (concert == null || boundId == concert.id) return
            boundId = concert.id
            title = concert.title
            date = concert.date
            endDate = concert.lastDay
            city = concert.city
            venue = concert.venue
            note = concert.note
            status = concert.status
            return
        }
        if (boundId != null) return
        boundId = "new"
        dateText.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let {
            date = it
            endDate = it
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConcertFormScreen(
    data: GroupDataViewModel,
    concertId: String?,
    initialDate: String,
    onBack: () -> Unit,
    onDone: (String?) -> Unit,
    model: ConcertFormViewModel = viewModel(),
) {
    val existing = concertId?.let { id -> data.concerts.find { it.id == id } }
    androidx.compose.runtime.LaunchedEffect(concertId, existing?.id, initialDate) {
        model.load(concertId, existing, initialDate)
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val memberIds = data.members.map { it.uid }.toSet()
    val rangeDays = daysIn(model.date, model.endDate)
    val blocked = rangeDays
        .flatMap { day -> blockingAbsences(data.absences, day) }
        .distinctBy { it.uid }
        .filter { it.uid in memberIds }
    val unsure = data.members.filter { member ->
        rangeDays.any { day -> winningAbsence(data.absences, member.uid, day)?.status == Availability.MAYBE }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (concertId == null) "Новое событие" else "Событие") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Назад") }
            },
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = model.title,
                onValueChange = { model.title = it.take(119) },
                label = { Text("Название") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DateField("Начало", model.date) {
                model.date = it
                if (model.endDate.isBefore(it)) model.endDate = it
            }
            DateField("Конец", model.endDate) { model.endDate = it }
            OutlinedTextField(
                value = model.city,
                onValueChange = { model.city = it.take(80) },
                label = { Text("Город") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model.venue,
                onValueChange = { model.venue = it.take(80) },
                label = { Text("Место") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model.note,
                onValueChange = { model.note = it.take(499) },
                label = { Text("Заметка: время, сбор, что взять") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Статус", style = MaterialTheme.typography.labelLarge)
            ChoiceButtons(
                options = listOf(
                    "Идея" to (model.status == ConcertStatus.IDEA),
                    "Будет" to (model.status == ConcertStatus.CONFIRMED),
                    "Нет" to (model.status == ConcertStatus.CANCELLED),
                ),
                onSelect = {
                    model.status = listOf(ConcertStatus.IDEA, ConcertStatus.CONFIRMED, ConcertStatus.CANCELLED)[it]
                },
            )
            Text("Уже заняты в эти дни", style = MaterialTheme.typography.titleSmall)
            if (blocked.isEmpty()) {
                Text("Никто не поставил «нет» на эти дни.", style = MaterialTheme.typography.bodyMedium)
            } else {
                blocked.forEach { absence ->
                    val name = data.members.find { it.uid == absence.uid }?.displayName ?: "Участник"
                    Text("$name — нет", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (unsure.isNotEmpty()) {
                Text(
                    "Под вопросом: ${unsure.joinToString { it.displayName }}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!localError.isNullOrBlank()) Text(localError.orEmpty(), color = MaterialTheme.colorScheme.error)
            Button(
                onClick = {
                    if (model.title.isBlank()) {
                        localError = "Напишите название"
                        return@Button
                    }
                    if (model.endDate.isBefore(model.date)) {
                        localError = "Конец раньше начала"
                        return@Button
                    }
                    val draft = Concert(
                        id = existing?.id.orEmpty(),
                        title = model.title.trim(),
                        date = model.date,
                        endDate = model.endDate,
                        part = DayPart.EVENING,
                        city = model.city.trim(),
                        venue = model.venue.trim(),
                        note = model.note.trim(),
                        status = model.status,
                        createdBy = existing?.createdBy ?: data.uid,
                    )
                    scope.launch {
                        saving = true
                        localError = null
                        runCatching { data.saveConcert(draft) }
                            .onSuccess { id -> onDone(if (draft.id.isBlank()) id else null) }
                            .onFailure { localError = it.userMessage() }
                        saving = false
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(if (saving) "Сохраняем…" else "Сохранить") }
        }
    }
}

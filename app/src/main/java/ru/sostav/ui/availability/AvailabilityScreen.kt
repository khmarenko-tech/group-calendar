package ru.sostav.ui.availability

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import ru.sostav.logic.Absence
import ru.sostav.logic.Availability
import ru.sostav.logic.absenceLabel
import ru.sostav.logic.allDayParts
import ru.sostav.ui.GroupDataViewModel
import ru.sostav.ui.components.ChoiceButtons
import ru.sostav.ui.components.ConfirmDialog
import ru.sostav.ui.components.DateField
import ru.sostav.ui.components.EmptyText
import ru.sostav.ui.components.InboxButton
import ru.sostav.ui.components.StatusChip
import ru.sostav.ui.ruRange
import java.time.LocalDate

@Composable
fun AvailabilityScreen(
    data: GroupDataViewModel,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    onInbox: () -> Unit,
) {
    val mine = data.absences.filter { it.uid == data.uid }.sortedBy { it.start }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Мои отметки", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                InboxButton(data.unread, onInbox)
            }
            Text(
                "Подпись вроде «Макс минус» будет на каждом дне диапазона. Время пишите в заметке.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (mine.isEmpty()) {
                EmptyText("Пока нет отметок. Можно взять несколько дней сразу.")
            }
            mine.forEach { absence ->
                OutlinedButton(
                    onClick = { onEdit(absence.id) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            absenceLabel(absence, data.myName),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            absence.start.ruRange(absence.end),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    StatusChip(absence.status)
                }
            }
            Box(Modifier.heightIn(min = 88.dp))
        }
        ExtendedFloatingActionButton(
            onClick = onCreate,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Дни") },
        )
    }
}

class AbsenceFormViewModel : ViewModel() {
    var start by mutableStateOf(LocalDate.now())
    var end by mutableStateOf(LocalDate.now())
    var status by mutableStateOf(Availability.CANNOT)
    var note by mutableStateOf("")
    private var boundId: String? = null

    fun load(absenceId: String, absence: Absence?, date: LocalDate?) {
        if (absenceId.isNotBlank()) {
            if (absence == null || boundId == absence.id) return
            boundId = absence.id
            start = absence.start
            end = absence.end
            status = absence.status
            note = absence.note
            return
        }
        if (boundId != null) return
        boundId = "new"
        if (date != null) {
            start = date
            end = date
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AbsenceFormScreen(
    data: GroupDataViewModel,
    absenceId: String,
    initialDate: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
    model: AbsenceFormViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val existing = data.absences.find { it.id == absenceId }
    val parsedDate = initialDate.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    androidx.compose.runtime.LaunchedEffect(absenceId, existing?.id, initialDate) {
        model.load(absenceId, existing, parsedDate)
    }
    var confirmDelete by androidx.compose.runtime.remember { mutableStateOf(false) }
    var localError by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (existing == null) "Отметить дни" else "Отметка") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
        )
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = model.note,
                onValueChange = { model.note = it.take(299) },
                label = { Text("На каждом дне, например Макс минус") },
                modifier = Modifier.fillMaxWidth(),
            )
            DateField("Начало", model.start) {
                model.start = it
                if (model.end.isBefore(it)) model.end = it
            }
            DateField("Конец", model.end) { model.end = it }
            Text("Как отметиться", style = MaterialTheme.typography.labelLarge)
            ChoiceButtons(
                options = listOf(
                    "Могу" to (model.status == Availability.CAN),
                    "Нет" to (model.status == Availability.CANNOT),
                    "Может" to (model.status == Availability.MAYBE),
                ),
                onSelect = { model.status = listOf(Availability.CAN, Availability.CANNOT, Availability.MAYBE)[it] },
            )
            if (!localError.isNullOrBlank()) {
                Text(localError.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    if (model.end.isBefore(model.start)) {
                        localError = "Конец раньше начала"
                        return@Button
                    }
                    data.saveAbsence(
                        existing,
                        Absence(
                            id = existing?.id.orEmpty(),
                            uid = data.uid,
                            start = model.start,
                            end = model.end,
                            parts = allDayParts(),
                            status = model.status,
                            note = model.note.trim(),
                        ),
                    )
                    onDone()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Сохранить") }
            if (existing != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Удалить") }
            }
        }
    }
    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = "Удалить отметку?",
            body = "Она исчезнет с каждого дня у всей группы.",
            confirm = "Удалить",
            onConfirm = {
                data.deleteAbsence(existing)
                confirmDelete = false
                onDone()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

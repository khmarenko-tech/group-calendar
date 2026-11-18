package ru.sostav.ui.concert

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import ru.sostav.data.downloadYandexIcs
import ru.sostav.data.userMessage
import ru.sostav.logic.ImportedEvent
import ru.sostav.logic.alreadyImported
import ru.sostav.logic.parseIcs
import ru.sostav.logic.toConcertDraft
import ru.sostav.ui.GroupDataViewModel
import ru.sostav.ui.components.ErrorText
import ru.sostav.ui.ruRange
import java.time.LocalDate

data class ImportRow(
    val event: ImportedEvent,
    val duplicate: Boolean,
    val selected: Boolean,
)

class YandexImportViewModel : ViewModel() {
    var url by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)
    var rows by mutableStateOf<List<ImportRow>>(emptyList())
        private set

    val selectedCount: Int get() = rows.count { it.selected && !it.duplicate }

    fun selectAll(on: Boolean) {
        rows = rows.map { row ->
            if (row.duplicate) row else row.copy(selected = on)
        }
    }

    fun toggle(uid: String) {
        rows = rows.map { row ->
            if (row.event.uid == uid && !row.duplicate) row.copy(selected = !row.selected) else row
        }
    }

    fun loadUrl(existing: List<ru.sostav.logic.Concert>) {
        val link = url.trim()
        if (link.isBlank()) {
            error = "Вставьте ссылку iCal из Яндекс.Календаря"
            return
        }
        viewModelScope.launch {
            loading = true
            error = null
            info = null
            runCatching { downloadYandexIcs(link) }
                .onSuccess { applyText(it, existing) }
                .onFailure { error = it.userMessage() }
            loading = false
        }
    }

    fun loadText(text: String, existing: List<ru.sostav.logic.Concert>) {
        error = null
        info = null
        applyText(text, existing)
    }

    fun importSelected(data: GroupDataViewModel, onDone: () -> Unit) {
        viewModelScope.launch {
            val drafts = rows.filter { it.selected && !it.duplicate }
                .map { toConcertDraft(it.event, data.uid) }
            if (drafts.isEmpty()) {
                error = "Отметьте события, которых ещё нет в группе"
                return@launch
            }
            saving = true
            error = null
            runCatching { data.importConcerts(drafts) }
                .onSuccess { onDone() }
                .onFailure { error = it.userMessage() }
            saving = false
        }
    }

    private fun applyText(text: String, existing: List<ru.sostav.logic.Concert>) {
        val parsed = runCatching { parseIcs(text) }
        if (parsed.isFailure) {
            error = "Не удалось прочитать календарь. Нужен файл iCal из Яндекса."
            rows = emptyList()
            return
        }
        val today = LocalDate.now()
        val events = parsed.getOrDefault(emptyList())
            .filter { !it.lastDayTooOld(today) }
            .take(120)
        if (events.isEmpty()) {
            error = "В календаре нет ближайших событий"
            rows = emptyList()
            return
        }
        rows = events.map { event ->
            val duplicate = alreadyImported(event, existing)
            ImportRow(
                event = event,
                duplicate = duplicate,
                selected = !duplicate && defaultImport(event, today),
            )
        }
        val fresh = rows.count { it.selected }
        val copies = rows.count { it.duplicate }
        info = buildString {
            append("Нашли ${rows.size}")
            if (fresh > 0) append(" · к добавлению $fresh")
            if (copies > 0) append(" · уже есть $copies")
        }
    }
}

private fun ImportedEvent.lastDayTooOld(today: LocalDate): Boolean =
    endDate.isBefore(today.minusDays(14))

private fun defaultImport(event: ImportedEvent, today: LocalDate): Boolean {
    if (event.cancelled || event.endDate.isBefore(today)) return false
    val title = event.title.lowercase()
    if ("минус" in title) return false
    if ("оплата" in title || "аренда" in title) return false
    return true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YandexImportScreen(
    data: GroupDataViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    model: YandexImportViewModel = viewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: error("empty")
        }.onSuccess { model.loadText(it, data.concerts) }
            .onFailure { model.error = "Не удалось открыть файл" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Яндекс.Календарь") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Назад") }
                },
            )
        },
        bottomBar = {
            if (model.rows.isNotEmpty()) {
                Button(
                    onClick = { model.importSelected(data, onDone) },
                    enabled = !model.saving && !model.loading && model.selectedCount > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        if (model.saving) "Добавляем…"
                        else "Добавить в группу: ${model.selectedCount}",
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "В Яндекс.Календаре откройте нужный календарь → настройки → «Экспорт» → скопируйте ссылку iCal. Ссылку в приложение, пароль Яндекса не нужен.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                OutlinedTextField(
                    value = model.url,
                    onValueChange = { model.url = it },
                    label = { Text("Ссылка iCal") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
            item {
                OutlinedButton(
                    onClick = { clipboard.getText()?.text?.let { model.url = it } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Вставить из буфера") }
            }
            item {
                Button(
                    onClick = { model.loadUrl(data.concerts) },
                    enabled = !model.loading && !model.saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(if (model.loading) "Загружаю…" else "Загрузить по ссылке") }
            }
            item {
                OutlinedButton(
                    onClick = { file.launch(arrayOf("text/calendar", "text/plain", "*/*")) },
                    enabled = !model.loading && !model.saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Или выбрать файл .ics") }
            }
            item { ErrorText(model.error) }
            if (!model.info.isNullOrBlank()) {
                item { Text(model.info.orEmpty(), style = MaterialTheme.typography.bodyMedium) }
            }
            if (model.rows.any { !it.duplicate }) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { model.selectAll(true) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text("Все") }
                        OutlinedButton(
                            onClick = { model.selectAll(false) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text("Снять") }
                    }
                }
            }
            items(model.rows, key = { "${it.event.uid}|${it.event.title}" }) { row ->
                ImportEventRow(row, onToggle = { model.toggle(row.event.uid) })
            }
        }
    }
}

@Composable
private fun ImportEventRow(row: ImportRow, onToggle: () -> Unit) {
    val event = row.event
    val whenText = event.date.ruRange(event.endDate)
    val extra = listOf(
        event.timeText,
        event.location,
        if (event.repeat != ru.sostav.logic.RepeatPeriod.NONE) {
            when (event.repeat) {
                ru.sostav.logic.RepeatPeriod.DAILY -> "каждый день"
                ru.sostav.logic.RepeatPeriod.WEEKLY -> "каждую неделю"
                ru.sostav.logic.RepeatPeriod.MONTHLY -> "каждый месяц"
                ru.sostav.logic.RepeatPeriod.YEARLY -> "каждый год"
                ru.sostav.logic.RepeatPeriod.NONE -> ""
            }
        } else "",
    ).filter { it.isNotBlank() }.joinToString(" · ")
    val subtitle = if (row.duplicate) {
        listOf(whenText, extra, "уже в группе").filter { it.isNotBlank() }.joinToString(" · ")
    } else {
        listOf(whenText, extra).filter { it.isNotBlank() }.joinToString(" · ")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = !row.duplicate, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = row.selected,
            onCheckedChange = { if (!row.duplicate) onToggle() },
            enabled = !row.duplicate,
        )
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(event.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

package ru.sostav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.sostav.logic.Availability
import ru.sostav.logic.ConcertStatus
import ru.sostav.ui.dateToMillis
import ru.sostav.ui.millisToDate
import ru.sostav.ui.ru
import ru.sostav.ui.ruFull
import java.time.LocalDate

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(text)
        }
    }
}

@Composable
fun StatusChip(status: Availability?) {
    val (background, foreground) = statusColors(status)
    Text(
        text = status?.ru() ?: "Нет отметки",
        color = foreground,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
fun concertColor(status: ConcertStatus): Color = when (status) {
    ConcertStatus.CONFIRMED -> MaterialTheme.colorScheme.primary
    ConcertStatus.IDEA -> MaterialTheme.colorScheme.tertiary
    ConcertStatus.CANCELLED -> MaterialTheme.colorScheme.outline
}

@Composable
fun concertOnColor(status: ConcertStatus): Color = when (status) {
    ConcertStatus.CONFIRMED -> MaterialTheme.colorScheme.onPrimary
    ConcertStatus.IDEA -> MaterialTheme.colorScheme.onTertiary
    ConcertStatus.CANCELLED -> MaterialTheme.colorScheme.onSurface
}

@Composable
fun availabilityDot(status: Availability?): Color {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    return when (status) {
        Availability.CANNOT -> MaterialTheme.colorScheme.error
        Availability.MAYBE -> if (dark) Color(0xFFFBBF24) else Color(0xFFB45309)
        Availability.CAN -> if (dark) Color(0xFF6EE7B7) else Color(0xFF047857)
        null -> MaterialTheme.colorScheme.outline
    }
}

@Composable
fun StripeRow(
    title: String,
    subtitle: String,
    stripe: Color,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(36.dp)
                .background(stripe, RoundedCornerShape(2.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun ConcertChip(status: ConcertStatus) {
    Text(
        text = status.ru(),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun statusColors(status: Availability?): Pair<Color, Color> {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    return when (status) {
        Availability.CANNOT -> if (dark) Color(0xFF7F1D1D) to Color(0xFFFECACA) else Color(0xFFFEE2E2) to Color(0xFF991B1B)
        Availability.MAYBE -> if (dark) Color(0xFF78350F) to Color(0xFFFDE68A) else Color(0xFFFEF3C7) to Color(0xFF92400E)
        Availability.CAN -> if (dark) Color(0xFF064E3B) to Color(0xFFA7F3D0) else Color(0xFFD1FAE5) to Color(0xFF065F46)
        null -> if (dark) Color(0xFF334155) to Color(0xFFE2E8F0) else Color(0xFFE2E8F0) to Color(0xFF1E293B)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, date: LocalDate, onChange: (LocalDate) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(date.ruFull())
        }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = dateToMillis(date))
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(millisToDate(it)) }
                    open = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Отмена") } },
        ) {
            DatePicker(state)
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun InboxButton(unread: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (unread > 0) "Уведомления $unread" else "Уведомления")
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
fun ChoiceButtons(
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (label, selected) ->
            val modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            if (selected) {
                Button(onClick = { onSelect(index) }, modifier = modifier) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                }
            } else {
                OutlinedButton(onClick = { onSelect(index) }, modifier = modifier) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                }
            }
        }
    }
}

@Composable
fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}

@Composable
fun ErrorText(text: String?) {
    if (!text.isNullOrBlank()) {
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ScreenColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

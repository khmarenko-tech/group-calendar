package ru.sostav.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.sostav.ui.GroupDataViewModel
import ru.sostav.ui.components.EmptyText
import ru.sostav.ui.formatNoticeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(data: GroupDataViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Уведомления") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
            actions = {
                if (data.unread > 0) {
                    TextButton(onClick = data::markAllRead, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Прочитать все")
                    }
                }
            },
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (data.notices.isEmpty()) EmptyText("Пока пусто. Здесь появятся новые концерты и отметки «не могу».")
            data.notices.forEach { notice ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable {
                            data.markRead(notice.id)
                            if (notice.concertId.isNotBlank()) onOpen(notice.concertId)
                        }
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        if (notice.read) notice.title else "Новое · ${notice.title}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (notice.read) FontWeight.Normal else FontWeight.SemiBold,
                    )
                    Text(notice.body, style = MaterialTheme.typography.bodyLarge)
                    if (notice.createdAt > 0L) {
                        Text(
                            formatNoticeTime(notice.createdAt),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

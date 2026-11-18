package ru.sostav.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.sostav.ui.GroupDataViewModel

@Composable
fun UpdatesBanner(
    data: GroupDataViewModel,
    onOpenInbox: () -> Unit,
    onOpenConcert: (String) -> Unit,
) {
    val unread = data.notices.filterNot { it.read }.take(3)
    if (unread.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                if (data.unread == 1) "Что изменилось" else "Что изменилось · ${data.unread}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            unread.forEach { notice ->
                Text(
                    notice.body,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .clickable {
                            data.markRead(notice.id)
                            if (notice.concertId.isNotBlank()) onOpenConcert(notice.concertId)
                            else onOpenInbox()
                        }
                        .padding(vertical = 6.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (data.unread > unread.size) {
                    TextButton(onClick = onOpenInbox) { Text("Все") }
                }
                TextButton(onClick = data::markAllRead) { Text("Скрыть") }
            }
        }
    }
}

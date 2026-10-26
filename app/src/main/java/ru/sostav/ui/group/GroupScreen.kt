package ru.sostav.ui.group

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.sostav.BuildConfig
import ru.sostav.data.Graph
import ru.sostav.data.Member
import ru.sostav.data.MemberRole
import ru.sostav.data.userMessage
import ru.sostav.ui.GroupDataViewModel
import ru.sostav.ui.components.ConfirmDialog
import ru.sostav.ui.components.InboxButton
import java.util.Locale

@Composable
fun GroupScreen(data: GroupDataViewModel, onSignOut: () -> Unit, onInbox: () -> Unit) {
    val group = data.group
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var name by rememberSaveable(group?.name) { mutableStateOf(group?.name.orEmpty()) }
    var myName by rememberSaveable(data.myName) { mutableStateOf(data.me?.displayName ?: data.myName) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmCode by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = data.members.find { it.uid == selectedId }
    val collator = java.text.Collator.getInstance(Locale.forLanguageTag("ru"))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Группа", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            InboxButton(data.unread, onInbox)
        }
        Text(
            group?.inviteCode.orEmpty().ifBlank { "······" },
            fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 4.sp,
        )
        Text("Код приглашения. Человек вводит его после регистрации.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val code = group?.inviteCode.orEmpty()
                    if (code.isBlank()) return@OutlinedButton
                    clipboard.setText(AnnotatedString(code))
                    message = "Код скопирован"
                    error = null
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("Копировать") }
            OutlinedButton(
                onClick = {
                    val code = group?.inviteCode.orEmpty()
                    val title = group?.name.orEmpty()
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Код группы «$title» в приложении «Календарь группы»: $code")
                    }
                    context.startActivity(Intent.createChooser(send, "Отправить код"))
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("Отправить") }
        }
        if (data.isAdmin) {
            OutlinedButton(
                onClick = { confirmCode = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Новый код") }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(59) },
                label = { Text("Название группы") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    scope.launch {
                        runCatching { Graph.groups.rename(data.groupId, name, group?.inviteCode.orEmpty()) }
                            .onSuccess { message = "Название сохранено"; error = null }
                            .onFailure { error = it.userMessage(); message = null }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Сохранить название") }
        }
        OutlinedTextField(
            value = myName,
            onValueChange = { myName = it.take(59) },
            label = { Text("Ваше имя") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                scope.launch {
                    runCatching { Graph.groups.renameMe(data.groupId, data.uid, myName) }
                        .onSuccess {
                            data.myName = myName.trim()
                            message = "Имя сохранено"
                            error = null
                        }
                        .onFailure { error = it.userMessage(); message = null }
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Сохранить имя") }
        Text("Участники", style = MaterialTheme.typography.titleMedium)
        data.members.sortedWith { a, b -> collator.compare(a.displayName, b.displayName) }.forEach { member ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    buildString {
                        append(member.displayName)
                        if (member.uid == data.uid) append(" (вы)")
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    listOf(if (member.role == MemberRole.ADMIN) "админ" else "участник", member.email)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (data.isAdmin && member.uid != data.uid) {
                    TextButton(onClick = { selectedId = member.uid }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Действия")
                    }
                }
            }
        }
        if (!message.isNullOrBlank()) Text(message.orEmpty(), color = MaterialTheme.colorScheme.secondary)
        if (!error.isNullOrBlank()) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        OutlinedButton(
            onClick = { confirmLeave = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Выйти из группы") }
        TextButton(onClick = onSignOut, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Выйти из аккаунта")
        }
        Text(
            "Версия ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmCode) {
        ConfirmDialog(
            title = "Сменить код?",
            body = "Старый код перестанет работать. Новый увидите на этом экране.",
            confirm = "Сменить",
            onConfirm = {
                confirmCode = false
                scope.launch {
                    runCatching {
                        Graph.groups.rotateCode(data.groupId, group?.name.orEmpty(), group?.inviteCode.orEmpty())
                    }.onSuccess { message = "Код обновлён"; error = null }
                        .onFailure { error = it.userMessage(); message = null }
                }
            },
            onDismiss = { confirmCode = false },
        )
    }
    if (confirmLeave) {
        ConfirmDialog(
            title = "Выйти из группы?",
            body = "Ваши отметки «могу / не могу» будут удалены. Войти снова можно по коду.",
            confirm = "Выйти",
            onConfirm = {
                confirmLeave = false
                scope.launch {
                    runCatching { Graph.groups.leave(data.groupId, data.members, data.uid) }
                        .onFailure { error = it.userMessage() }
                }
            },
            onDismiss = { confirmLeave = false },
        )
    }
    selected?.let { member ->
        MemberDialog(
            member = member,
            onDismiss = { selectedId = null },
            onPromote = {
                scope.launch {
                    runCatching { Graph.groups.setRole(data.groupId, data.members, member.uid, MemberRole.ADMIN) }
                        .onFailure { error = it.userMessage() }
                    selectedId = null
                }
            },
            onDemote = {
                scope.launch {
                    runCatching { Graph.groups.setRole(data.groupId, data.members, member.uid, MemberRole.MEMBER) }
                        .onFailure { error = it.userMessage() }
                    selectedId = null
                }
            },
            onRemove = {
                scope.launch {
                    runCatching { Graph.groups.removeMember(data.groupId, data.members, data.uid, member.uid) }
                        .onFailure { error = it.userMessage() }
                    selectedId = null
                }
            },
        )
    }
}

@Composable
private fun MemberDialog(
    member: Member,
    onDismiss: () -> Unit,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
    onRemove: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(member.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (member.role == MemberRole.MEMBER) {
                    TextButton(onClick = onPromote, modifier = Modifier.heightIn(min = 48.dp)) { Text("Сделать админом") }
                } else {
                    TextButton(onClick = onDemote, modifier = Modifier.heightIn(min = 48.dp)) { Text("Снять права админа") }
                }
                TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) { Text("Удалить из группы") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

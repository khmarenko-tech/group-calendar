package ru.sostav.ui.gate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import ru.sostav.data.Graph
import ru.sostav.data.Profile
import ru.sostav.data.userMessage
import ru.sostav.ui.components.ErrorText
import ru.sostav.ui.components.PrimaryButton
import ru.sostav.ui.components.SectionTitle

class GateViewModel : ViewModel() {
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var previewName by mutableStateOf<String?>(null)
    var previewCode by mutableStateOf("")

    fun create(profile: Profile, name: String) {
        loading = true
        error = null
        viewModelScope.launch {
            runCatching { Graph.groups.createGroup(profile, name) }
                .onFailure { error = it.userMessage() }
            loading = false
        }
    }

    fun lookup(code: String) {
        loading = true
        error = null
        previewName = null
        viewModelScope.launch {
            runCatching { Graph.groups.previewInvite(code) }
                .onSuccess {
                    previewName = it
                    previewCode = code.trim().uppercase()
                }
                .onFailure { error = it.userMessage() }
            loading = false
        }
    }

    fun join(profile: Profile) {
        loading = true
        error = null
        viewModelScope.launch {
            runCatching { Graph.groups.join(profile, previewCode) }
                .onFailure { error = it.userMessage() }
            loading = false
        }
    }
}

@Composable
fun GroupGateScreen(profile: Profile, onSignOut: () -> Unit, model: GateViewModel = viewModel()) {
    var groupName by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Здравствуйте, ${profile.displayName}", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Создайте группу или войдите по коду. Один аккаунт — одна группа.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionTitle("Создать группу")
        OutlinedTextField(
            value = groupName,
            onValueChange = { groupName = it },
            label = { Text("Название") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton("Создать", loading = model.loading, onClick = { model.create(profile, groupName) })
        SectionTitle("Войти по коду")
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase() },
            label = { Text("Код из 6 символов") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton("Проверить код", loading = model.loading, onClick = { model.lookup(code) })
        model.previewName?.let { name ->
            Text("Группа «$name»", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            PrimaryButton("Вступить", loading = model.loading, onClick = { model.join(profile) })
        }
        ErrorText(model.error)
        TextButton(onClick = onSignOut) { Text("Выйти из аккаунта") }
    }
}

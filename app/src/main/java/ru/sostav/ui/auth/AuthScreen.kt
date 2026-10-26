package ru.sostav.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import ru.sostav.data.Graph
import ru.sostav.data.userMessage
import ru.sostav.ui.components.ErrorText
import ru.sostav.ui.components.PrimaryButton

class AuthViewModel : ViewModel() {
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)

    fun clearNotice() {
        error = null
        info = null
    }

    fun submit(register: Boolean, name: String, email: String, password: String) {
        loading = true
        error = null
        info = null
        viewModelScope.launch {
            runCatching {
                if (register) Graph.accounts.register(name, email, password)
                else Graph.accounts.signIn(email, password)
            }.onFailure { error = it.userMessage() }
            loading = false
        }
    }

    fun reset(email: String) {
        loading = true
        error = null
        info = null
        viewModelScope.launch {
            runCatching { Graph.accounts.resetPassword(email) }
                .onSuccess { info = "Письмо со ссылкой ушло на эту почту. Откройте его и задайте новый пароль." }
                .onFailure { error = it.userMessage() }
            loading = false
        }
    }
}

@Composable
fun AuthScreen(model: AuthViewModel = viewModel()) {
    var register by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (register) "Регистрация" else "Календарь группы",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    if (register) {
                        "Напишите, как вас видит группа. Потом создадите группу или войдёте по коду."
                    } else {
                        "Войдите, если аккаунт уже есть. Если вы здесь впервые, создайте его внизу экрана."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (register) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя в группе") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Почта") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Пароль") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showPassword) "Скрыть пароль" else "Показать пароль",
                        )
                    }
                },
                supportingText = {
                    Text(if (register) "Не короче 8 символов" else "Тот, что задавали при регистрации")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(model.error)
            if (!model.info.isNullOrBlank()) {
                Text(
                    model.info.orEmpty(),
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            PrimaryButton(
                text = if (register) "Зарегистрироваться" else "Войти",
                loading = model.loading,
                onClick = { model.submit(register, name, email, password) },
            )
            if (register) {
                TextButton(
                    onClick = {
                        register = false
                        model.clearNotice()
                    },
                    enabled = !model.loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Уже есть аккаунт? Войти") }
            } else {
                TextButton(
                    onClick = { model.reset(email) },
                    enabled = !model.loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Не помню пароль") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Первый раз в приложении?",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        register = true
                        model.clearNotice()
                    },
                    enabled = !model.loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Зарегистрироваться") }
            }
        }
    }
}

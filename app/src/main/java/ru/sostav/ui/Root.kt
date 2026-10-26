package ru.sostav.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.sostav.BuildConfig
import ru.sostav.data.Graph
import ru.sostav.data.Session
import ru.sostav.ui.auth.AuthScreen
import ru.sostav.ui.components.PrimaryButton
import ru.sostav.ui.gate.GroupGateScreen
import ru.sostav.ui.home.Home

class SessionViewModel : ViewModel() {
    val session = Graph.accounts.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Session.Loading)

    fun signOut() {
        viewModelScope.launch { runCatching { Graph.accounts.signOut() } }
    }
}

@Composable
fun Root() {
    if (!BuildConfig.FIREBASE_CONFIGURED) {
        SetupScreen()
        return
    }
    AuthenticatedRoot()
}

@Composable
private fun AuthenticatedRoot(sessionViewModel: SessionViewModel = viewModel()) {
    val session by sessionViewModel.session.collectAsState()
    when (val current = session) {
        Session.Loading -> Centered { CircularProgressIndicator() }
        Session.SignedOut -> AuthScreen()
        is Session.Failed -> FailedScreen(current.message, sessionViewModel::signOut)
        is Session.Ready -> {
            val groupId = current.profile.groupId
            if (groupId == null) {
                GroupGateScreen(current.profile, onSignOut = sessionViewModel::signOut)
            } else {
                Home(current.profile, onSignOut = sessionViewModel::signOut)
            }
        }
    }
}

@Composable
private fun FailedScreen(message: String, onSignOut: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("Не удалось открыть данные", style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyLarge)
        PrimaryButton("Выйти из аккаунта", onSignOut)
    }
}

@Composable
private fun SetupScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("Календарь группы ещё не подключён", style = MaterialTheme.typography.titleLarge)
        Text(
            "В папке app нет файла google-services.json. Скачайте его из Firebase и соберите приложение заново. Шаги лежат в файле НАСТРОЙКА.txt.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = { content() },
    )
}

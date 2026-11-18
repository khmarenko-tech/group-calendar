package ru.sostav.ui.update

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import ru.sostav.BuildConfig
import ru.sostav.update.UpdateOffer
import ru.sostav.update.fetchUpdateOffer
import ru.sostav.update.openUpdatePage

class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    var offer by mutableStateOf<UpdateOffer?>(null)
        private set
    var info by mutableStateOf<String?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    private var dismissed = false
    private var lastCheck = 0L

    fun check(force: Boolean = false) {
        if (checking) return
        if (offer != null && !force) return
        if (dismissed && !force) return
        val now = System.currentTimeMillis()
        if (!force && lastCheck != 0L && now - lastCheck < 5 * 60 * 1000L) return
        lastCheck = now
        viewModelScope.launch {
            checking = true
            info = null
            val result = runCatching { fetchUpdateOffer(BuildConfig.VERSION_NAME) }
            checking = false
            val found = result.getOrNull()
            if (found != null) {
                dismissed = false
                offer = found
                return@launch
            }
            if (force) {
                info = if (result.isFailure) {
                    "Не удалось проверить обновление. Проверьте интернет."
                } else {
                    "Уже стоит последняя версия ${BuildConfig.VERSION_NAME}"
                }
            }
        }
    }

    fun later() {
        dismissed = true
        offer = null
    }

    fun open() {
        val current = offer ?: return
        openUpdatePage(getApplication(), current)
    }
}

@Composable
fun rememberUpdateViewModel(): UpdateViewModel {
    val activity = LocalContext.current as ComponentActivity
    return viewModel(viewModelStoreOwner = activity)
}

@Composable
fun UpdatePrompt() {
    val model = rememberUpdateViewModel()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { model.check() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.check()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val current = model.offer ?: return
    AlertDialog(
        onDismissRequest = model::later,
        title = { Text("Есть версия ${current.version}") },
        text = {
            Text(
                "Сейчас стоит ${BuildConfig.VERSION_NAME}. Файл откроется на GitHub в браузере. Приложение само ничего не ставит.",
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = { TextButton(onClick = model::open) { Text("Открыть GitHub") } },
        dismissButton = { TextButton(onClick = model::later) { Text("Позже") } },
    )
}

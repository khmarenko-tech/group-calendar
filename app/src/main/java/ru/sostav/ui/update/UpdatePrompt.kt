package ru.sostav.ui.update

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.sostav.BuildConfig
import ru.sostav.update.UpdateOffer
import ru.sostav.update.canRequestInstall
import ru.sostav.update.downloadUpdate
import ru.sostav.update.fetchUpdateOffer
import ru.sostav.update.openInstallPermission
import ru.sostav.update.openInstaller
import ru.sostav.update.verifyUpdate
import java.io.File

internal sealed interface UpdateState {
    data object Hidden : UpdateState
    data class Offer(val offer: UpdateOffer) : UpdateState
    data class Downloading(val offer: UpdateOffer, val fraction: Float) : UpdateState
    data class Ready(val offer: UpdateOffer, val file: File) : UpdateState
    data class NeedPermission(val offer: UpdateOffer, val file: File) : UpdateState
    data class Failed(val offer: UpdateOffer, val message: String) : UpdateState
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    internal var state by mutableStateOf<UpdateState>(UpdateState.Hidden)
        private set
    private var job: Job? = null
    private var asked = false
    private var session = 0

    fun check() {
        if (asked || state !is UpdateState.Hidden) return
        asked = true
        viewModelScope.launch {
            val offer = runCatching { fetchUpdateOffer(BuildConfig.VERSION_NAME) }.getOrNull() ?: return@launch
            if (state is UpdateState.Hidden) state = UpdateState.Offer(offer)
        }
    }

    fun later() {
        session += 1
        job?.cancel()
        val file = (state as? UpdateState.Ready)?.file ?: (state as? UpdateState.NeedPermission)?.file
        file?.delete()
        state = UpdateState.Hidden
    }

    fun download() {
        val offer = when (val current = state) {
            is UpdateState.Offer -> current.offer
            is UpdateState.Failed -> current.offer
            else -> return
        }
        val ticket = session
        job?.cancel()
        job = viewModelScope.launch {
            val file = File(getApplication<Application>().cacheDir, "updates/update.apk")
            state = UpdateState.Downloading(offer, 0f)
            try {
                downloadUpdate(offer, file) { fraction ->
                    if (ticket == session) state = UpdateState.Downloading(offer, fraction)
                }
            } catch (cancelled: CancellationException) {
                file.delete()
                throw cancelled
            } catch (_: Exception) {
                file.delete()
                if (ticket == session) {
                    state = UpdateState.Failed(offer, "Не удалось скачать обновление. Проверьте интернет и повторите.")
                }
                return@launch
            }
            if (ticket != session) return@launch
            val problem = verifyUpdate(getApplication(), file)
            if (problem != null) {
                file.delete()
                state = UpdateState.Failed(offer, problem)
                return@launch
            }
            if (!canRequestInstall(getApplication())) {
                state = UpdateState.NeedPermission(offer, file)
            } else {
                state = UpdateState.Ready(offer, file)
                openInstaller(getApplication(), file)
            }
        }
    }

    fun allowInstall() {
        openInstallPermission(getApplication())
    }

    fun onResume() {
        val waiting = state as? UpdateState.NeedPermission ?: return
        if (!canRequestInstall(getApplication())) return
        state = UpdateState.Ready(waiting.offer, waiting.file)
        openInstaller(getApplication(), waiting.file)
    }

    fun install() {
        val ready = state as? UpdateState.Ready ?: return
        if (!canRequestInstall(getApplication())) {
            state = UpdateState.NeedPermission(ready.offer, ready.file)
            return
        }
        openInstaller(getApplication(), ready.file)
    }
}

@Composable
fun UpdatePrompt() {
    val model: UpdateViewModel = viewModel()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { model.check() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.onResume()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val context = LocalContext.current
    when (val current = model.state) {
        UpdateState.Hidden -> Unit
        is UpdateState.Offer -> UpdateDialog(
            title = "Есть версия ${current.offer.version}",
            body = "Сейчас стоит ${BuildConfig.VERSION_NAME}. Файл скачается с GitHub. Перед установкой приложение сверит пакет, номер сборки и подпись. Чужой файл не откроется.",
            confirm = "Скачать и обновить",
            onConfirm = model::download,
            onDismiss = model::later,
        )
        is UpdateState.Downloading -> UpdateDialog(
            title = "Скачивание ${current.offer.version}",
            body = "Файл идёт с GitHub. После загрузки подпись сверится сама.",
            progress = current.fraction,
            dismiss = "Отмена",
            onDismiss = model::later,
        )
        is UpdateState.NeedPermission -> UpdateDialog(
            title = "Нужно разрешение",
            body = "Android один раз спрашивает, можно ли «${context.applicationInfo.loadLabel(context.packageManager)}» ставить свои обновления. Подпись файла уже совпала.",
            confirm = "Разрешить установку",
            onConfirm = model::allowInstall,
            onDismiss = model::later,
        )
        is UpdateState.Ready -> UpdateDialog(
            title = "Версия ${current.offer.version} проверена",
            body = "Подпись совпадает с этим приложением. Осталось подтвердить установку в окне Android.",
            confirm = "Установить",
            onConfirm = model::install,
            onDismiss = model::later,
        )
        is UpdateState.Failed -> UpdateDialog(
            title = "Обновление не поставлено",
            body = current.message,
            confirm = "Повторить",
            onConfirm = model::download,
            onDismiss = model::later,
        )
    }
}

@Composable
private fun UpdateDialog(
    title: String,
    body: String,
    confirm: String? = null,
    dismiss: String = "Позже",
    progress: Float? = null,
    onConfirm: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body, style = MaterialTheme.typography.bodyLarge)
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            if (confirm != null) TextButton(onClick = onConfirm) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss) } },
    )
}

package io.ml.jupyroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ml.jupyroid.data.ServerConfig
import io.ml.jupyroid.data.ServerMode

@Composable
fun SettingsDialog(
    config: ServerConfig,
    runtimeInstalled: Boolean,
    onSave: (ServerConfig) -> Unit,
    onShowLog: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(config.mode) }
    var url by remember { mutableStateOf(config.externalUrl) }
    var token by remember { mutableStateOf(config.token) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Jupyter-сервер", style = MaterialTheme.typography.titleSmall)
                ModeOption("Встроенный Linux (Debian внутри приложения)", mode == ServerMode.Embedded) { mode = ServerMode.Embedded }
                ModeOption("Внешний сервер (компьютер, Termux, …)", mode == ServerMode.External) { mode = ServerMode.External }
                if (mode == ServerMode.External) {
                    OutlinedTextField(url, { url = it }, label = { Text("Адрес") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(token, { token = it }, label = { Text("Токен") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Например: jupyter server --ip=0.0.0.0 --ServerApp.token=<токен> на компьютере в той же сети.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider()
                Text("Встроенное окружение", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onShowLog) { Text("Лог установки и сервера") }
                if (runtimeInstalled) {
                    TextButton(onClick = { confirmUninstall = true }) {
                        Text("Удалить окружение (Debian и все пакеты)", color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    "Пакеты ставятся в терминале: pip install … (попадут в окружение ядра) или apt install ….",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                TextButton(onClick = { licenses = true }) { Text("Сторонние компоненты и лицензии") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ServerConfig(mode, url, token)); onDismiss() }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text("Удалить окружение?") },
            text = {
                Text("Будут удалены Debian, Python, все установленные пакеты и файлы в домашней папке (~), включая ноутбуки. Это не отменить.")
            },
            confirmButton = {
                TextButton(onClick = { confirmUninstall = false; onUninstall(); onDismiss() }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("Отмена") } },
        )
    }
    if (licenses) LicensesDialog { licenses = false }
}

@Composable
private fun ModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

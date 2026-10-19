package io.ml.jupyroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ml.jupyroid.AppViewModel
import io.ml.jupyroid.runtime.RuntimeState
import io.ml.jupyroid.ui.theme.codeColors

/** First run: download Debian and install Python + Jupyter into it. */
@Composable
fun SetupScreen(vm: AppViewModel, state: RuntimeState, log: List<String>, onShowLog: () -> Unit) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Встроенный Linux для Jupyter", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Jupyroid скачает минимальный Debian (образ debian:bookworm-slim из Docker Hub, ~28 МБ) и " +
                    "поставит в него Python и Jupyter. Нужен интернет и пара минут. На диске займёт около 350 МБ, " +
                    "с научным набором — около 650 МБ. Всё живёт внутри приложения, Termux не нужен.",
                style = MaterialTheme.typography.bodyMedium,
            )
            when (state) {
                RuntimeState.Unsupported -> Text(
                    "Это устройство не arm64 — встроенный Linux здесь не запустится. Можно подключиться " +
                        "к внешнему Jupyter-серверу в ⚙ настройках.",
                    color = MaterialTheme.colorScheme.error,
                )
                is RuntimeState.Installing -> {
                    Text(state.step.label + "…", style = MaterialTheme.typography.titleMedium)
                    val p = state.progress
                    if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = vm.runtime::cancelInstall) { Text("Отменить") }
                }
                else -> {
                    if (state is RuntimeState.InstallFailed) {
                        Text("Установка не удалась: ${state.message}", color = MaterialTheme.colorScheme.error)
                    }
                    Row(
                        Modifier.clickable { vm.installScience = !vm.installScience },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = vm.installScience, onCheckedChange = { vm.installScience = it })
                        Text("Сразу поставить numpy, pandas и matplotlib")
                    }
                    Button(onClick = vm::installRuntime) {
                        Text(if (state is RuntimeState.InstallFailed) "Повторить установку" else "Установить")
                    }
                }
            }
            if (log.isNotEmpty()) {
                LogTail(log, onShowLog)
            }
            Text(
                "Уже есть Jupyter на компьютере или в Termux? В ⚙ можно переключиться на внешний сервер.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LogTail(log: List<String>, onShowLog: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(codeColors.editorBackground, RoundedCornerShape(8.dp))
            .clickable(onClick = onShowLog)
            .padding(12.dp),
    ) {
        log.takeLast(12).forEach {
            Text(it, style = CodeTextStyle.copy(fontSize = 11.sp, lineHeight = 15.sp), maxLines = 1)
        }
        Text("весь лог →", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun LogDialog(log: List<String>, onDismiss: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.lastIndex) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Лог окружения и сервера") },
        text = {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .background(codeColors.editorBackground, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                ) {
                    items(log) { line ->
                        Text(line, style = CodeTextStyle.copy(fontSize = 11.sp, lineHeight = 15.sp))
                    }
                    if (log.isEmpty()) item { Text("Пока пусто") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сторонние компоненты") },
        text = {
            Text(
                """
                proot — GPL-2.0, https://github.com/termux/proot
                (бинарник из пакетов Termux, исходники там же)
                talloc — LGPL-3.0, https://talloc.samba.org
                libandroid-shmem — BSD-3-Clause, https://github.com/termux/libandroid-shmem
                Termux terminal-emulator / terminal-view — Apache-2.0, https://github.com/termux/termux-app
                Material Symbols (значки файлов) — Apache-2.0, https://fonts.google.com/icons
                OkHttp — Apache-2.0 · kotlinx.serialization — Apache-2.0
                Debian и пакеты внутри окружения — их собственные лицензии (см. /usr/share/doc).
                """.trimIndent(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.height(260.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

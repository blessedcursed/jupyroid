package io.ml.jupyroid.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ml.jupyroid.AppViewModel
import io.ml.jupyroid.ServerStatus
import io.ml.jupyroid.data.ContentItem
import io.ml.jupyroid.runtime.RuntimeState

@Composable
fun FilesPanel(vm: AppViewModel, onOpen: (String) -> Unit, onShowLog: () -> Unit, modifier: Modifier = Modifier) {
    var renaming by remember { mutableStateOf<ContentItem?>(null) }
    var deleting by remember { mutableStateOf<ContentItem?>(null) }

    Column(modifier.fillMaxSize()) {
        ServerCard(vm, onShowLog)
        if (vm.server is ServerStatus.Online) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.openDirectory(vm.directory.substringBeforeLast('/', "")) }, enabled = vm.directory.isNotEmpty()) {
                    Icon(Icons.Default.KeyboardArrowUp, "Вверх")
                }
                Text(
                    "~/" + vm.directory,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = vm::refreshFiles) { Icon(Icons.Default.Refresh, "Обновить") }
                NewMenu(vm)
            }
            HorizontalDivider()
            vm.filesError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.items, key = { it.path }) { item ->
                    FileRow(
                        item = item,
                        open = vm.tabs.any { it.id == item.path },
                        openable = item.isDirectory || vm.canOpen(item),
                        onClick = { if (item.isDirectory) vm.openDirectory(item.path) else onOpen(item.path) },
                        onRename = { renaming = item },
                        onDelete = { deleting = item },
                    )
                }
            }
        }
    }

    renaming?.let { item ->
        var name by remember(item) { mutableStateOf(item.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Переименовать") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { vm.rename(item, name.trim()); renaming = null }, enabled = name.isNotBlank()) {
                    Text("OK")
                }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Отмена") } },
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить ${item.name}?") },
            text = { Text(if (item.isDirectory) "Папка будет удалена вместе с содержимым." else "Файл будет удалён без корзины.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(item); deleting = null }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun NewMenu(vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.Add, "Создать") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Ноутбук") }, onClick = { open = false; vm.newNotebook() })
            DropdownMenuItem(text = { Text("Python-файл") }, onClick = { open = false; vm.newFile(".py") })
            DropdownMenuItem(text = { Text("Markdown-файл") }, onClick = { open = false; vm.newFile(".md") })
            DropdownMenuItem(text = { Text("Текстовый файл") }, onClick = { open = false; vm.newFile(".txt") })
            DropdownMenuItem(text = { Text("Папку") }, onClick = { open = false; vm.newFolder() })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: ContentItem,
    open: Boolean,
    openable: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileIcon(item.name, item.isDirectory)
            Spacer(Modifier.width(12.dp))
            Text(
                item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (open) FontWeight.SemiBold else null,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (openable) 1f else 0.5f),
                modifier = Modifier.weight(1f),
            )
            if (open) Text("●", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun ServerCard(vm: AppViewModel, onShowLog: () -> Unit) {
    val runtimeState by vm.runtime.state.collectAsState()
    val server = vm.server
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (dot, label) = when {
                server is ServerStatus.Online -> Color(0xFF2DA44E) to "Jupyter Server ${server.version}"
                vm.embedded && runtimeState is RuntimeState.Installing -> Color(0xFFD29922) to "Идёт установка окружения"
                vm.embedded && runtimeState == RuntimeState.Starting -> Color(0xFFD29922) to "Jupyter запускается…"
                server is ServerStatus.Offline -> Color(0xFFCF222E) to server.reason
                else -> Color(0xFF8C959F) to "Проверяю сервер…"
            }
            Text("●", color = dot, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 2, modifier = Modifier.weight(1f))
        }
        if (vm.embedded && runtimeState !is RuntimeState.NotInstalled && runtimeState !is RuntimeState.Installing &&
            runtimeState !is RuntimeState.Unsupported && runtimeState !is RuntimeState.InstallFailed
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val running = runtimeState == RuntimeState.Running || runtimeState == RuntimeState.Starting
                SmallTextButton(if (running) "Стоп" else "Запустить") {
                    if (running) vm.stopEmbeddedServer() else vm.startEmbeddedServer()
                }
                if (running) SmallTextButton("Перезапуск", vm::restartEmbeddedServer)
                SmallTextButton("Лог", onShowLog)
            }
        } else if (!vm.embedded && server !is ServerStatus.Online) {
            SmallTextButton("Проверить", vm::recheck)
        }
    }
    HorizontalDivider()
}

@Composable
private fun SmallTextButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(32.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
        Text(label, fontSize = 13.sp)
    }
}

package io.ml.jupyroid.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ml.jupyroid.AppViewModel
import io.ml.jupyroid.notebook.NotebookSession
import io.ml.jupyroid.runtime.RuntimeState
import io.ml.jupyroid.workspace.FileKind
import io.ml.jupyroid.workspace.FileSession
import io.ml.jupyroid.workspace.TerminalTab
import io.ml.jupyroid.workspace.WorkspaceTab

@Composable
fun MainScreen(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    var settingsOpen by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    val runtimeState by vm.runtime.state.collectAsState()
    val runtimeLog by vm.runtime.log.collectAsState()
    val runtimeInstalled = runtimeState is RuntimeState.Stopped || runtimeState is RuntimeState.Starting ||
        runtimeState is RuntimeState.Running || runtimeState is RuntimeState.Crashed

    // The server's foreground notification (with its Stop button) needs this on Android 13+.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val active = vm.tabs.firstOrNull { it.id == vm.activeId }
    val message = vm.message ?: active?.message
    LaunchedEffect(message) {
        if (message != null) {
            vm.message = null
            active?.message = null
            snackbar.showSnackbar(message)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            val showTab = { id: String ->
                vm.activeId = id
                if (!wide) vm.showFiles = false
            }
            BackHandler(enabled = !wide && !vm.showFiles) { vm.showFiles = true }

            Column(Modifier.fillMaxSize()) {
                TopBar(
                    vm,
                    terminalEnabled = runtimeInstalled,
                    onSelectTab = showTab,
                    onTerminal = { vm.openTerminal(); if (!wide) vm.showFiles = false },
                    onSettings = { settingsOpen = true },
                )
                HorizontalDivider()
                Row(Modifier.fillMaxSize()) {
                    if (vm.showFiles) {
                        FilesPanel(
                            vm,
                            onOpen = { path -> vm.open(path); if (!wide && vm.activeId == path) vm.showFiles = false },
                            onShowLog = { logOpen = true },
                            modifier = if (wide) Modifier.width(300.dp) else Modifier.fillMaxWidth(),
                        )
                        if (wide) VerticalDivider()
                    }
                    if (wide || !vm.showFiles) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            when {
                                active != null -> key(active.id) { TabContent(vm, active) }
                                vm.embedded && !runtimeInstalled ->
                                    SetupScreen(vm, runtimeState, runtimeLog, onShowLog = { logOpen = true })
                                else -> EmptyWorkspace()
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (settingsOpen) {
        SettingsDialog(
            vm.config,
            runtimeInstalled = runtimeInstalled,
            onSave = vm::updateConfig,
            onShowLog = { logOpen = true },
            onUninstall = vm::uninstallRuntime,
            onDismiss = { settingsOpen = false },
        )
    }
    if (logOpen) LogDialog(runtimeLog) { logOpen = false }
}

@Composable
private fun TabContent(vm: AppViewModel, tab: WorkspaceTab) {
    when (tab) {
        is NotebookSession -> NotebookScreen(tab)
        is FileSession -> FileScreen(
            tab,
            // Running needs the file inside the embedded Debian, where Jupyter's root is /root.
            onRun = if (tab.kind == FileKind.Python && vm.embedded) ({ vm.runPythonFile(tab.path) }) else null,
        )
        is TerminalTab -> TerminalScreen(tab, onRestart = { vm.replaceFinishedTerminal(tab) })
        else -> Unit
    }
}

@Composable
private fun TopBar(
    vm: AppViewModel,
    terminalEnabled: Boolean,
    onSelectTab: (String) -> Unit,
    onTerminal: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.showFiles = !vm.showFiles }) { Icon(Icons.Default.Menu, "Файлы") }
        LazyRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (vm.tabs.isEmpty()) {
                item { Text("Jupyroid", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            }
            items(vm.tabs, key = { it.id }) { tab ->
                TabChip(
                    tab = tab,
                    active = tab.id == vm.activeId,
                    onClick = { onSelectTab(tab.id) },
                    onClose = { vm.closeTab(tab) },
                )
            }
        }
        if (terminalEnabled) {
            Box(
                Modifier.size(48.dp).clickable(onClick = onTerminal),
                contentAlignment = Alignment.Center,
            ) {
                TerminalIcon()
            }
        }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Настройки") }
    }
}

@Composable
private fun TabChip(tab: WorkspaceTab, active: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tab is TerminalTab) {
            TerminalIcon(size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            tab.persistPath?.let { FileIcon(it.substringAfterLast('/'), size = 16.dp) }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            (if (tab.dirty) "● " else "") + tab.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 14.sp,
            fontWeight = if (active) FontWeight.SemiBold else null,
            modifier = Modifier.widthIn(max = 180.dp),
        )
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, "Закрыть", Modifier.size(16.dp))
        }
    }
}

@Composable
private fun EmptyWorkspace() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FileIcon("x.ipynb", size = 56.dp)
        Spacer(Modifier.height(12.dp))
        Text("Открой файл слева, создай новый (+) или открой терминал", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "Shift+Enter — выполнить и перейти дальше · Ctrl+Enter — выполнить\n" +
                "Tab — автодополнение · Shift+Tab — убрать отступ · Ctrl+/ — комментарий\n" +
                "Ctrl+P — параметры функции · Ctrl+I — справка · Ctrl+S — сохранить\n" +
                "В терминале: pip install … ставит пакеты прямо в окружение ядра",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

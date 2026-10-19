package io.ml.jupyroid

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.ml.jupyroid.data.ContentItem
import io.ml.jupyroid.data.JupyterApi
import io.ml.jupyroid.data.JupyterException
import io.ml.jupyroid.data.ServerConfig
import io.ml.jupyroid.data.ServerMode
import io.ml.jupyroid.data.Settings
import io.ml.jupyroid.notebook.NotebookSession
import io.ml.jupyroid.runtime.RuntimeState
import io.ml.jupyroid.workspace.FileSession
import io.ml.jupyroid.workspace.TerminalTab
import io.ml.jupyroid.workspace.WorkspaceTab
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface ServerStatus {
    data object Checking : ServerStatus
    data class Online(val version: String) : ServerStatus
    data class Offline(val reason: String) : ServerStatus
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val jupyroid = app as JupyroidApp
    private val settings = Settings(app)
    private val http = jupyroid.http

    /** The embedded Linux + its Jupyter server. */
    val runtime = jupyroid.runtime

    var config by mutableStateOf(settings.load())
        private set
    private var api = JupyterApi(http, config)

    val embedded: Boolean get() = config.mode == ServerMode.Embedded

    var server by mutableStateOf<ServerStatus>(ServerStatus.Checking)
        private set

    var directory by mutableStateOf(settings.lastDirectory)
        private set
    var items by mutableStateOf<List<ContentItem>>(emptyList())
        private set
    var filesError by mutableStateOf<String?>(null)
        private set

    val tabs = mutableStateListOf<WorkspaceTab>()
    var activeId by mutableStateOf<String?>(null)
    var showFiles by mutableStateOf(true)
    var message by mutableStateOf<String?>(null)
    var installScience by mutableStateOf(settings.installScience)

    private var restoredTabs = false
    private var monitor: Job? = null
    private var terminalCounter = 0

    init {
        if (embedded && runtime.state.value == RuntimeState.Stopped) runtime.startServer(config.token)
        startMonitor()
    }

    /** Polls the server: fast while it's starting, slow once it's up (to notice when it dies). */
    private fun startMonitor() {
        monitor?.cancel()
        monitor = viewModelScope.launch {
            while (true) {
                check()
                val starting = embedded && runtime.state.value == RuntimeState.Starting
                delay(if (server is ServerStatus.Online) 15_000 else if (starting) 700 else 3_000)
            }
        }
    }

    private suspend fun check() {
        val previous = server
        server = try {
            ServerStatus.Online(api.status())
        } catch (e: Exception) {
            ServerStatus.Offline(
                when {
                    e is JupyterException && e.code == 403 -> "Сервер отвечает, но токен не подходит"
                    embedded -> when (val s = runtime.state.value) {
                        RuntimeState.Starting -> "Jupyter запускается…"
                        is RuntimeState.Crashed -> s.message
                        else -> "Jupyter не запущен"
                    }
                    else -> "Сервер не отвечает (${config.url})"
                },
            )
        }
        if (server is ServerStatus.Online && previous !is ServerStatus.Online) onConnected()
    }

    private fun onConnected() {
        openDirectory(directory)
        if (!restoredTabs) {
            restoredTabs = true
            viewModelScope.launch {
                // Files deleted or renamed outside the app would only reopen as "404" tabs.
                val saved = settings.openTabs.filter { runCatching { api.exists(it) }.getOrDefault(true) }
                saved.forEach { open(it, activate = false) }
                if (activeId == null) activeId = saved.lastOrNull()
                persistTabs()
            }
        }
        // Kernel sockets gave up while the server was down; poke them.
        tabs.filterIsInstance<NotebookSession>().forEach { it.reconnect() }
    }

    fun recheck() {
        server = ServerStatus.Checking
        startMonitor()
    }

    fun updateConfig(newConfig: ServerConfig) {
        // Open tabs hold the old API client; reopen them against the new server once it answers.
        val open = tabs.mapNotNull { it.persistPath }
        tabs.filter { it.persistPath != null }.forEach { closeTab(it) }
        settings.openTabs = open
        restoredTabs = false
        val wasEmbedded = embedded
        settings.save(newConfig)
        config = settings.load()
        api = JupyterApi(http, config)
        if (embedded && runtime.state.value == RuntimeState.Stopped) runtime.startServer(config.token)
        if (wasEmbedded && !embedded) runtime.stopServerAsync()
        recheck()
    }

    // ---- embedded runtime ----------------------------------------------------------------

    fun installRuntime() {
        settings.installScience = installScience
        runtime.install(installScience) { if (embedded) runtime.startServer(config.token) }
    }

    fun startEmbeddedServer() = runtime.startServer(config.token)
    fun stopEmbeddedServer() = runtime.stopServerAsync()
    fun restartEmbeddedServer() = runtime.restartServer(config.token)

    fun uninstallRuntime() {
        tabs.filterIsInstance<TerminalTab>().forEach { closeTab(it) }
        runtime.uninstall()
    }

    // ---- files ---------------------------------------------------------------------------

    fun openDirectory(path: String) {
        viewModelScope.launch {
            try {
                items = api.listDirectory(path)
                directory = path
                settings.lastDirectory = path
                filesError = null
            } catch (e: Exception) {
                if (path.isNotEmpty() && e is JupyterException && e.code == 404) {
                    openDirectory("")
                } else {
                    filesError = e.message
                }
            }
        }
    }

    fun refreshFiles() = openDirectory(directory)

    fun newNotebook() = create("ноутбук") { api.createNotebook(directory) }
    fun newFile(extension: String) = create("файл") { api.createFile(directory, extension) }

    fun newFolder() = viewModelScope.launch {
        try {
            api.createDirectory(directory)
            refreshFiles()
        } catch (e: Exception) {
            message = "Не удалось создать папку: ${e.message}"
        }
    }

    private fun create(what: String, block: suspend () -> String) = viewModelScope.launch {
        try {
            val path = block()
            refreshFiles()
            open(path)
        } catch (e: Exception) {
            message = "Не удалось создать $what: ${e.message}"
        }
    }

    fun rename(item: ContentItem, newName: String) = viewModelScope.launch {
        val newPath = item.path.substringBeforeLast('/', "").let { if (it.isEmpty()) newName else "$it/$newName" }
        try {
            api.rename(item.path, newPath)
            refreshFiles()
        } catch (e: Exception) {
            message = "Не удалось переименовать: ${e.message}"
        }
    }

    fun delete(item: ContentItem) = viewModelScope.launch {
        try {
            tabs.firstOrNull { it.persistPath == item.path }?.let { closeTab(it, save = false) }
            api.delete(item.path)
            refreshFiles()
        } catch (e: Exception) {
            message = "Не удалось удалить: ${e.message}"
        }
    }

    // ---- tabs ----------------------------------------------------------------------------

    fun canOpen(item: ContentItem) = item.isNotebook || FileSession.isTextFile(item.name)

    /** Opens a notebook or text file in a tab (or focuses the tab if it's already open). */
    fun open(path: String, activate: Boolean = true) {
        if (tabs.none { it.id == path }) {
            val name = path.substringAfterLast('/')
            val tab = when {
                name.endsWith(".ipynb") -> NotebookSession(path, api, viewModelScope).also { it.load() }
                FileSession.isTextFile(name) -> FileSession(path, api, viewModelScope).also { it.load() }
                else -> {
                    message = "Такие файлы пока не открываются"
                    return
                }
            }
            tabs += tab
            persistTabs()
        }
        if (activate) activeId = path
    }

    /** Terminals need the embedded Linux (they run bash inside it), regardless of server mode. */
    val terminalAvailable: Boolean get() = runtime.runtime.isInstalled

    fun openTerminal(command: String? = null, label: String? = null) {
        if (!terminalAvailable) {
            message = "Терминалу нужно встроенное окружение — установи его"
            return
        }
        val tab = TerminalTab(++terminalCounter, runtime.runtime, getApplication(), command, label)
        tabs += tab
        activeId = tab.id
    }

    /** Server paths are relative to Jupyter's root, /root, in the embedded Debian. */
    fun runPythonFile(path: String) {
        val tab = tabs.firstOrNull { it.id == path }
        viewModelScope.launch {
            tab?.saveNow(quiet = true)
            openTerminal("python3 " + TerminalTab.shellQuote("/root/$path"), "▶ ${path.substringAfterLast('/')}")
        }
    }

    fun replaceFinishedTerminal(tab: TerminalTab) {
        val index = tabs.indexOf(tab)
        closeTab(tab)
        openTerminal()
        if (index >= 0) tabs.add(index.coerceAtMost(tabs.lastIndex), tabs.removeAt(tabs.lastIndex))
    }

    /** Closing a notebook keeps its kernel running (like JupyterLab) so reopening finds its variables. */
    fun closeTab(tab: WorkspaceTab, save: Boolean = true) {
        val index = tabs.indexOf(tab)
        tabs.remove(tab)
        if (activeId == tab.id) activeId = tabs.getOrNull(index.coerceAtMost(tabs.lastIndex))?.id
        persistTabs()
        viewModelScope.launch {
            if (save && tab.dirty) tab.saveNow(quiet = true)
            tab.dispose()
        }
    }

    fun saveAll() = tabs.forEach { if (it.dirty) it.save(quiet = true) }

    private fun persistTabs() {
        settings.openTabs = tabs.mapNotNull { it.persistPath }
    }

    override fun onCleared() {
        tabs.forEach { it.dispose() }
    }
}

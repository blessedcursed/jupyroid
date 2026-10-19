package io.ml.jupyroid.notebook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import io.ml.jupyroid.data.ExecEvent
import io.ml.jupyroid.data.JupyterApi
import io.ml.jupyroid.data.KernelClient
import io.ml.jupyroid.data.KernelSession
import io.ml.jupyroid.data.KernelState
import io.ml.jupyroid.editor.CallContexts
import io.ml.jupyroid.editor.EditOps
import io.ml.jupyroid.editor.Signature
import io.ml.jupyroid.editor.SignatureParser
import io.ml.jupyroid.model.Cell
import io.ml.jupyroid.model.CellType
import io.ml.jupyroid.model.Notebook
import io.ml.jupyroid.model.Output
import io.ml.jupyroid.model.appendStreamText
import io.ml.jupyroid.workspace.WorkspaceTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class RunState { Idle, Queued, Running }

class CellState(cell: Cell) {
    val id = cell.id
    var type by mutableStateOf(cell.type)
    var text by mutableStateOf(TextFieldValue(cell.source))
    val outputs = mutableStateListOf<Output>().apply { addAll(cell.outputs) }
    var executionCount by mutableStateOf(cell.executionCount)
    var run by mutableStateOf(RunState.Idle)
    /** Markdown cells show rendered text unless being edited. */
    var editing by mutableStateOf(cell.type != CellType.Markdown || cell.source.isBlank())
    private val metadata = cell.metadata
    private val attachments = cell.attachments

    /** Set by clear_output(wait=True): clear just before the next output arrives (no flicker). */
    var clearBeforeNextOutput = false

    fun toCell() = Cell(id, type, text.text, metadata, outputs.toList(), executionCount, attachments)
}

data class CompletionUi(val cellId: String, val matches: List<String>, val start: Int, val end: Int)

/** Parameter info for the call around the cursor; the active argument is recomputed from the cursor. */
data class ParamInfoUi(val cellId: String, val openParen: Int, val callee: String, val signature: Signature)

sealed interface LoadState {
    data object Loading : LoadState
    data object Ready : LoadState
    data class Failed(val message: String) : LoadState
}

/** One open notebook: its cells, its kernel connection and everything the editor does to them. */
class NotebookSession(
    val path: String,
    private val api: JupyterApi,
    parentScope: CoroutineScope,
) : WorkspaceTab {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    val name: String get() = path.substringAfterLast('/')
    override val id: String get() = path
    override val title: String get() = name.removeSuffix(".ipynb")
    override val persistPath: String get() = path

    var loadState by mutableStateOf<LoadState>(LoadState.Loading)
        private set
    val cells = mutableStateListOf<CellState>()
    var selectedId by mutableStateOf<String?>(null)

    /** A cell that should take focus and scroll into view; consumed by the UI. */
    var focusRequest by mutableStateOf<String?>(null)
    override var dirty by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    override var message by mutableStateOf<String?>(null)
    var completion by mutableStateOf<CompletionUi?>(null)
        private set
    var tooltip by mutableStateOf<String?>(null)
    var paramInfo by mutableStateOf<ParamInfoUi?>(null)
        private set
    private var paramJob: Job? = null

    var kernel by mutableStateOf<KernelClient?>(null)
        private set
    var kernelName by mutableStateOf<String?>(null)
        private set
    private val noKernel = MutableStateFlow(KernelState.Connecting)
    val kernelState: StateFlow<KernelState> get() = kernel?.state ?: noKernel

    private var notebook: Notebook = Notebook.empty()
    private var session: KernelSession? = null

    val selected: CellState? get() = cells.firstOrNull { it.id == selectedId }

    fun load() {
        loadState = LoadState.Loading
        scope.launch {
            try {
                notebook = Notebook.fromJson(api.getNotebook(path))
                cells.clear()
                val loaded = notebook.cells.ifEmpty { listOf(Cell.new(CellType.Code)) }
                cells.addAll(loaded.map(::CellState))
                selectedId = cells.first().id
                loadState = LoadState.Ready
                startKernel()
            } catch (e: Exception) {
                loadState = LoadState.Failed(e.message ?: e.toString())
            }
        }
        scope.launch {
            while (isActive) {
                delay(AUTOSAVE_MS)
                if (dirty && !saving && loadState == LoadState.Ready) save(quiet = true)
            }
        }
    }

    private suspend fun startKernel() {
        try {
            val name = notebook.kernelName ?: api.kernelSpecs().first
            val s = api.startSession(path, name)
            session = s
            kernelName = s.kernelName
            kernel?.close()
            kernel = KernelClient(api, s.kernelId, scope).also {
                it.onUpdateDisplay = ::updateDisplay
                it.connect()
            }
            if (notebook.kernelName == null) {
                // New notebooks come without a kernelspec; record it so the file reopens with the same kernel.
                val specs = api.kernelSpecs().second
                val spec = specs.firstOrNull { it.name == s.kernelName }
                notebook = notebook.copy(metadata = JsonObject(notebook.metadata + ("kernelspec" to buildJsonObject {
                    put("name", s.kernelName)
                    put("display_name", spec?.displayName ?: s.kernelName)
                    put("language", spec?.language ?: "python")
                })))
            }
        } catch (e: Exception) {
            message = "Не удалось запустить ядро: ${e.message}"
        }
    }

    // ---- editing -------------------------------------------------------------------------

    fun onTextChange(cell: CellState, value: TextFieldValue) {
        val old = cell.text
        val newValue = EditOps.autoIndent(old, value)
        if (newValue.text != old.text) {
            dirty = true
            completion = null
            tooltip = null
        }
        cell.text = newValue
        if (cell.type == CellType.Code) updateParamInfo(cell, old, newValue)
    }

    /** Like an IDE: pops up after typing `(`, follows the cursor, closes when it leaves the call. */
    private fun updateParamInfo(cell: CellState, old: TextFieldValue, new: TextFieldValue) {
        val cursor = new.selection.start
        val typedParen = new.text.length > old.text.length && new.selection.collapsed &&
            new.text.getOrNull(cursor - 1) == '('
        val info = paramInfo
        when {
            typedParen -> requestParamInfo(cell, explicit = false)
            info != null && info.cellId == cell.id -> {
                val ctx = CallContexts.find(new.text, cursor)
                if (ctx == null) paramInfo = null
                else if (ctx.openParen != info.openParen) requestParamInfo(cell, explicit = false)
            }
        }
    }

    fun select(cell: CellState) {
        if (selectedId != cell.id) {
            completion = null
            paramInfo = null
        }
        selectedId = cell.id
    }

    /** Applies an edit to the selected cell (extra-keys bar, hardware shortcuts). */
    fun editSelected(op: (TextFieldValue) -> TextFieldValue) {
        val cell = selected ?: return
        onTextChange(cell, op(cell.text))
    }

    fun addCell(type: CellType = CellType.Code, below: Boolean = true, focus: Boolean = true): CellState {
        val index = cells.indexOfFirst { it.id == selectedId }
            .let { if (it < 0) cells.size else if (below) it + 1 else it }
        val cell = CellState(Cell.new(type))
        cells.add(index, cell)
        selectedId = cell.id
        if (focus) focusRequest = cell.id
        dirty = true
        return cell
    }

    fun deleteSelected() {
        val index = cells.indexOfFirst { it.id == selectedId }
        if (index < 0) return
        cells.removeAt(index)
        if (cells.isEmpty()) cells.add(CellState(Cell.new(CellType.Code)))
        selectedId = cells[index.coerceAtMost(cells.lastIndex)].id
        dirty = true
    }

    fun moveSelected(delta: Int) {
        val index = cells.indexOfFirst { it.id == selectedId }
        val target = index + delta
        if (index < 0 || target !in cells.indices) return
        cells.add(target, cells.removeAt(index))
        focusRequest = selectedId
        dirty = true
    }

    fun setType(cell: CellState, type: CellType) {
        if (cell.type == type) return
        cell.type = type
        cell.editing = true
        if (type != CellType.Code) {
            cell.outputs.clear()
            cell.executionCount = null
        }
        dirty = true
    }

    fun clearOutputs(cell: CellState) {
        cell.outputs.clear()
        cell.executionCount = null
        dirty = true
    }

    fun clearAllOutputs() = cells.forEach { clearOutputs(it) }

    /** Moves selection/focus to the neighbouring cell (arrow keys at the edge of a cell). */
    fun selectNeighbour(delta: Int): Boolean {
        val index = cells.indexOfFirst { it.id == selectedId } + delta
        if (index !in cells.indices) return false
        selectedId = cells[index].id
        focusRequest = cells[index].id
        return true
    }

    // ---- execution -----------------------------------------------------------------------

    fun run(cell: CellState) {
        paramInfo = null
        when (cell.type) {
            CellType.Markdown -> { cell.editing = cell.text.text.isBlank(); return }
            CellType.Raw -> return
            CellType.Code -> Unit
        }
        val k = kernel
        if (k == null || k.state.value == KernelState.Disconnected || k.state.value == KernelState.Dead) {
            message = "Ядро не подключено"
            return
        }
        cell.outputs.clear()
        cell.clearBeforeNextOutput = false
        cell.executionCount = null
        cell.run = RunState.Queued
        dirty = true
        k.execute(cell.text.text) { event -> onExecEvent(cell, event) }
    }

    private fun onExecEvent(cell: CellState, event: ExecEvent) {
        when (event) {
            is ExecEvent.Out -> {
                if (cell.clearBeforeNextOutput) {
                    cell.outputs.clear()
                    cell.clearBeforeNextOutput = false
                }
                val out = event.output
                val last = cell.outputs.lastOrNull()
                if (out is Output.Stream && last is Output.Stream && last.name == out.name) {
                    cell.outputs[cell.outputs.lastIndex] = last.copy(text = appendStreamText(last.text, out.text))
                } else if (out is Output.Stream) {
                    cell.outputs.add(out.copy(text = appendStreamText("", out.text)))
                } else {
                    cell.outputs.add(out)
                }
            }
            is ExecEvent.ClearOutput ->
                if (event.wait) cell.clearBeforeNextOutput = true else cell.outputs.clear()
            is ExecEvent.ExecutionCount -> {
                if (event.count != null) cell.executionCount = event.count
                cell.run = RunState.Running
            }
            is ExecEvent.Done -> cell.run = RunState.Idle
        }
    }

    /** update_display_data: replace every output carrying the same display_id, in any cell. */
    private fun updateDisplay(update: Output.Rich) {
        val displayId = update.displayId ?: return
        for (c in cells) {
            for (i in c.outputs.indices) {
                val o = c.outputs[i]
                if (o is Output.Rich && o.displayId == displayId) {
                    c.outputs[i] = o.copy(data = update.data, metadata = update.metadata)
                    dirty = true
                }
            }
        }
    }

    /** Shift+Enter: run, then move to the next cell, creating one at the end like Jupyter does. */
    fun runAndAdvance(cell: CellState) {
        run(cell)
        val index = cells.indexOf(cell)
        if (index == cells.lastIndex) {
            selectedId = cell.id
            addCell(CellType.Code)
        } else {
            val next = cells[index + 1]
            selectedId = next.id
            focusRequest = next.id
        }
    }

    fun runAll() = cells.forEach { run(it) }

    fun runAbove() {
        val index = cells.indexOfFirst { it.id == selectedId }
        cells.take(index).forEach { run(it) }
    }

    fun interrupt() {
        val id = session?.kernelId ?: return
        scope.launch { runCatching { api.interruptKernel(id) }.onFailure { message = "Interrupt: ${it.message}" } }
    }

    fun restart(thenRunAll: Boolean = false) {
        val id = session?.kernelId ?: return
        scope.launch {
            try {
                api.restartKernel(id)
                kernel?.finishAll("cancelled")
                cells.forEach { it.run = RunState.Idle }
                message = "Ядро перезапущено"
                if (thenRunAll) {
                    // Wait until the restarted kernel reports idle before queueing work on it.
                    delay(500)
                    var waited = 0
                    while (kernel?.state?.value != KernelState.Idle && waited < 20_000) {
                        delay(200)
                        waited += 200
                    }
                    runAll()
                }
            } catch (e: Exception) {
                message = "Перезапуск не удался: ${e.message}"
            }
        }
    }

    /**
     * Re-establishes the kernel after the server came back. Goes through POST /api/sessions again:
     * that returns the same kernel if it survived, or a fresh one if the server was restarted.
     */
    fun reconnect() {
        if (loadState != LoadState.Ready) return
        val state = kernel?.state?.value
        if (kernel == null || state == KernelState.Disconnected || state == KernelState.Connecting || state == KernelState.Dead) {
            scope.launch { startKernel() }
        }
    }

    /** Stops the kernel for good (closing the tab alone keeps it running, like JupyterLab). */
    fun shutdownKernel() {
        val s = session ?: return
        scope.launch {
            runCatching { api.deleteSession(s.sessionId) }
            kernel?.close()
            kernel = null
            session = null
            cells.forEach { it.run = RunState.Idle }
            message = "Ядро остановлено"
        }
    }

    // ---- completion & help ---------------------------------------------------------------

    /** Tab: completes at the cursor when there's something to complete, otherwise indents. */
    fun tab() {
        val cell = selected ?: return
        if (cell.type == CellType.Code && EditOps.wantsCompletion(cell.text)) {
            requestCompletion(cell)
        } else {
            editSelected(EditOps::indent)
        }
    }

    fun requestCompletion(cell: CellState) {
        val k = kernel ?: return
        val value = cell.text
        scope.launch {
            val result = k.complete(value.text, value.selection.start) ?: return@launch
            if (cell.text != value) return@launch // edited meanwhile
            when (result.matches.size) {
                0 -> Unit
                1 -> onTextChange(cell, EditOps.replace(value, result.cursorStart, result.cursorEnd, result.matches[0]))
                else -> completion = CompletionUi(cell.id, result.matches.distinct(), result.cursorStart, result.cursorEnd)
            }
        }
    }

    fun applyCompletion(match: String) {
        val c = completion ?: return
        val cell = cells.firstOrNull { it.id == c.cellId } ?: return
        completion = null
        onTextChange(cell, EditOps.replace(cell.text, c.start, c.end, match))
        focusRequest = cell.id
    }

    fun dismissCompletion() {
        completion = null
        tooltip = null
        paramInfo = null
    }

    /** Docs for the object at [position] (default: the cursor) in the selected cell. */
    fun showHelp(position: Int? = null) {
        val cell = selected ?: return
        val k = kernel ?: return
        scope.launch {
            tooltip = k.inspect(cell.text.text, position ?: cell.text.selection.start) ?: "Справка не найдена"
        }
    }

    /** Ctrl+P: the signature of the function whose call the cursor is in, asked from the kernel. */
    fun requestParamInfo(target: CellState? = null, explicit: Boolean = true) {
        val cell = target ?: selected ?: return
        val k = kernel ?: return
        val ctx = CallContexts.find(cell.text.text, cell.text.selection.start)
        if (ctx == null) {
            paramInfo = null
            if (explicit) message = "Курсор не внутри вызова функции"
            return
        }
        paramJob?.cancel()
        paramJob = scope.launch {
            if (!explicit) delay(120) // typing "f(" quickly shouldn't fire twice
            val sig = k.inspect(cell.text.text, ctx.calleeEnd)?.let { SignatureParser.parse(it, ctx.callee) }
            // Ignore the answer if the cursor moved to another call in the meantime.
            if (CallContexts.find(cell.text.text, cell.text.selection.start)?.openParen != ctx.openParen) return@launch
            if (sig == null) {
                paramInfo = null
                if (explicit) message = "Нет сигнатуры для ${ctx.callee} — выполни ячейку, где он импортируется"
            } else {
                paramInfo = ParamInfoUi(cell.id, ctx.openParen, ctx.callee, sig)
            }
        }
    }

    fun showParamDocs() {
        val info = paramInfo ?: return
        val cell = cells.firstOrNull { it.id == info.cellId } ?: return
        CallContexts.find(cell.text.text, cell.text.selection.start)?.let { showHelp(it.calleeEnd) }
    }

    // ---- persistence ---------------------------------------------------------------------

    override fun save(quiet: Boolean) {
        if (loadState != LoadState.Ready) return
        scope.launch { saveNow(quiet) }
    }

    override suspend fun saveNow(quiet: Boolean) {
        if (saving) return
        saving = true
        val snapshot = notebook.copy(cells = cells.map { it.toCell() })
        val wasDirtyVersion = cells.map { it.text.text }
        try {
            api.saveNotebook(path, snapshot.toJson())
            // Only clear the flag if nothing was typed while the request was in flight.
            if (cells.map { it.text.text } == wasDirtyVersion) dirty = false
            if (!quiet) message = "Сохранено"
        } catch (e: Exception) {
            message = "Не удалось сохранить: ${e.message}"
        } finally {
            saving = false
        }
    }

    override fun dispose() {
        kernel?.close()
        scope.cancel()
    }

    companion object {
        private const val AUTOSAVE_MS = 30_000L
    }
}

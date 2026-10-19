package io.ml.jupyroid.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ml.jupyroid.data.KernelState
import io.ml.jupyroid.editor.CallContexts
import io.ml.jupyroid.editor.EditOps
import io.ml.jupyroid.model.CellType
import io.ml.jupyroid.notebook.CellState
import io.ml.jupyroid.notebook.LoadState
import io.ml.jupyroid.notebook.NotebookSession
import io.ml.jupyroid.notebook.RunState
import io.ml.jupyroid.ui.theme.codeColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotebookScreen(session: NotebookSession, modifier: Modifier = Modifier) {
    when (val state = session.loadState) {
        LoadState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is LoadState.Failed -> Column(
            modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Не удалось открыть ${session.name}", style = MaterialTheme.typography.titleMedium)
            Text(state.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
            Button(onClick = { session.load() }) { Text("Повторить") }
        }
        LoadState.Ready -> Column(modifier.fillMaxSize()) {
            NotebookToolbar(session)
            HorizontalDivider()
            CellList(session, Modifier.weight(1f))

            val imeVisible = WindowInsets.isImeVisible
            val editing = session.selected?.let { it.editing && it.type != CellType.Raw } == true
            session.tooltip?.let { HelpPanel(it) { session.tooltip = null } }
            session.paramInfo?.let { info ->
                // The active argument follows the cursor without asking the kernel again.
                val cell = session.cells.firstOrNull { it.id == info.cellId }
                val ctx = cell?.let { CallContexts.find(it.text.text, it.text.selection.start) }
                ParamInfoStrip(
                    signature = info.signature,
                    active = ctx?.let(info.signature::activeParam),
                    onDocs = session::showParamDocs,
                    onClose = session::dismissCompletion,
                )
            }
            session.completion?.let { c -> CompletionStrip(c.matches, session::applyCompletion, session::dismissCompletion) }
            if (imeVisible && editing) {
                CodeExtraKeys(
                    edit = session::editSelected,
                    onTab = session::tab,
                    onRun = { session.selected?.let(session::runAndAdvance) },
                    onHelp = { session.showHelp() },
                    onParamInfo = { session.requestParamInfo() },
                    onUp = {
                        val c = session.selected
                        if (c != null && EditOps.isOnFirstLine(c.text)) session.selectNeighbour(-1)
                        else session.editSelected { EditOps.move(it, EditOps.Move.Up) }
                    },
                    onDown = {
                        val c = session.selected
                        if (c != null && EditOps.isOnLastLine(c.text)) session.selectNeighbour(1)
                        else session.editSelected { EditOps.move(it, EditOps.Move.Down) }
                    },
                )
            }
        }
    }
}

@Composable
private fun CellList(session: NotebookSession, modifier: Modifier) {
    val listState = rememberLazyListState()

    // Scroll the requested cell into view; the cell itself then grabs focus once composed.
    val request = session.focusRequest
    LaunchedEffect(request) {
        val index = session.cells.indexOfFirst { it.id == request }
        if (index < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == index } || visible.last().index == index) {
            listState.animateScrollToItem(index, scrollOffset = -48)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 200.dp),
    ) {
        itemsIndexed(session.cells, key = { _, c -> c.id }) { _, cell ->
            CellView(session, cell, selected = cell.id == session.selectedId)
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 56.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { session.selectedId = session.cells.lastOrNull()?.id; session.addCell(CellType.Code) }) {
                    Text("+ Код")
                }
                TextButton(onClick = { session.selectedId = session.cells.lastOrNull()?.id; session.addCell(CellType.Markdown) }) {
                    Text("+ Текст")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CellView(session: NotebookSession, cell: CellState, selected: Boolean) {
    val focusRequester = remember { FocusRequester() }
    val accent = MaterialTheme.colorScheme.primary
    val colors = codeColors

    LaunchedEffect(session.focusRequest) {
        if (session.focusRequest == cell.id) {
            if (cell.type == CellType.Markdown && !cell.editing) {
                session.focusRequest = null
            } else {
                runCatching { focusRequester.requestFocus() }.onSuccess { session.focusRequest = null }
            }
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { if (selected) drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
            .clickable(remember { MutableInteractionSource() }, indication = null) { session.select(cell) }
            .padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
    ) {
        ExecutionPrompt(cell)
        Column(Modifier.weight(1f)) {
            if (selected) CellActions(session, cell)
            val borderColor = if (selected) accent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant
            val shape = RoundedCornerShape(6.dp)
            if (cell.type == CellType.Markdown && !cell.editing) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp)
                        .pointerInput(cell) {
                            detectTapGestures(
                                onTap = { session.select(cell) },
                                onDoubleTap = {
                                    session.select(cell)
                                    cell.editing = true
                                    session.focusRequest = cell.id
                                },
                            )
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    MarkdownView(cell.text.text)
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(colors.editorBackground)
                        .border(1.dp, borderColor, shape),
                ) {
                    CodeEditor(
                        value = cell.text,
                        onValueChange = { session.onTextChange(cell, it) },
                        highlight = cell.type == CellType.Code,
                        focusRequester = focusRequester,
                        onFocused = { session.select(cell) },
                        onKey = { handleKey(session, cell, it) },
                    )
                }
            }
            if (cell.outputs.isNotEmpty()) {
                SelectionContainer {
                    OutputsColumn(cell.outputs)
                }
            }
        }
    }
}

@Composable
private fun ExecutionPrompt(cell: CellState) {
    val text = when {
        cell.type != CellType.Code -> ""
        cell.run == RunState.Running -> "[*]"
        cell.run == RunState.Queued -> "[…]"
        else -> "[${cell.executionCount ?: " "}]"
    }
    Text(
        text,
        modifier = Modifier.width(52.dp).padding(start = 6.dp, top = 9.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = if (cell.run != RunState.Idle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun CellActions(session: NotebookSession, cell: CellState) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallIcon(Icons.Default.PlayArrow, "Выполнить") { session.runAndAdvance(cell) }
        if (cell.type == CellType.Markdown && !cell.editing) {
            SmallIcon(Icons.Default.Edit, "Редактировать") { cell.editing = true; session.focusRequest = cell.id }
        }
        SmallIcon(Icons.Default.KeyboardArrowUp, "Выше") { session.moveSelected(-1) }
        SmallIcon(Icons.Default.KeyboardArrowDown, "Ниже") { session.moveSelected(1) }
        SmallIcon(Icons.Default.Add, "Ячейка ниже") { session.addCell(CellType.Code) }
        TextButton(
            onClick = { session.setType(cell, if (cell.type == CellType.Code) CellType.Markdown else CellType.Code) },
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.height(32.dp),
        ) { Text(if (cell.type == CellType.Code) "Код → MD" else "MD → Код", fontSize = 12.sp) }
        if (cell.outputs.isNotEmpty()) SmallIcon(Icons.Default.Clear, "Очистить вывод") { session.clearOutputs(cell) }
        SmallIcon(Icons.Default.Delete, "Удалить ячейку") { session.deleteSelected() }
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

/** Hardware keyboard shortcuts, JupyterLab-style. */
private fun handleKey(session: NotebookSession, cell: CellState, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val ctrl = e.isCtrlPressed || e.isMetaPressed
    val plain = !ctrl && !e.isShiftPressed && !e.isAltPressed
    return when {
        e.key == Key.Enter && e.isShiftPressed -> { session.runAndAdvance(cell); true }
        e.key == Key.Enter && ctrl -> { session.run(cell); true }
        e.key == Key.Enter && e.isAltPressed -> { session.run(cell); session.addCell(CellType.Code); true }
        e.key == Key.Tab && e.isShiftPressed -> { session.editSelected(EditOps::dedent); true }
        e.key == Key.Tab && plain -> { session.tab(); true }
        e.key == Key.Spacebar && ctrl -> { session.requestCompletion(cell); true }
        e.key == Key.S && ctrl -> { session.save(quiet = false); true }
        e.key == Key.Slash && ctrl -> { session.editSelected(EditOps::toggleComment); true }
        e.key == Key.I && ctrl -> { session.showHelp(); true }
        e.key == Key.P && ctrl -> { session.requestParamInfo(cell); true }
        e.key == Key.Escape -> {
            val had = session.completion != null || session.tooltip != null
            session.dismissCompletion()
            had
        }
        e.key == Key.Backspace && plain -> { session.editSelected(EditOps::backspace); true }
        e.key == Key.DirectionUp && plain && EditOps.isOnFirstLine(cell.text) -> session.selectNeighbour(-1)
        e.key == Key.DirectionDown && plain && EditOps.isOnLastLine(cell.text) -> session.selectNeighbour(1)
        else -> false
    }
}

@Composable
private fun NotebookToolbar(session: NotebookSession) {
    val kernelState by session.kernelState.collectAsState()
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KernelChip(session, kernelState)
        Spacer(Modifier.width(4.dp))
        ToolbarButton("▶ Ячейка") { session.selected?.let(session::runAndAdvance) }
        ToolbarButton("▶▶ Все") { session.runAll() }
        ToolbarButton("■ Стоп") { session.interrupt() }
        ToolbarButton("↻ Рестарт") { session.restart() }
        ToolbarButton(if (session.saving) "Сохраняю…" else if (session.dirty) "● Сохранить" else "Сохранено") {
            session.save(quiet = false)
        }
    }
}

@Composable
private fun ToolbarButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(36.dp)) {
        Text(label, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun KernelChip(session: NotebookSession, state: KernelState) {
    var menu by remember { mutableStateOf(false) }
    val dot = when (state) {
        KernelState.Idle -> Color(0xFF2DA44E)
        KernelState.Busy, KernelState.Starting, KernelState.Restarting -> Color(0xFFD29922)
        KernelState.Connecting -> Color(0xFF8C959F)
        KernelState.Dead, KernelState.Disconnected -> Color(0xFFCF222E)
    }
    Box {
        Surface(
            onClick = { menu = true },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${session.kernelName ?: "ядро"} · ${state.label}",
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
                Icon(Icons.Default.MoreVert, null, Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Переподключиться") }, leadingIcon = { Icon(Icons.Default.Refresh, null) },
                onClick = { menu = false; session.reconnect() })
            DropdownMenuItem(text = { Text("Прервать выполнение") }, onClick = { menu = false; session.interrupt() })
            DropdownMenuItem(text = { Text("Перезапустить ядро") }, onClick = { menu = false; session.restart() })
            DropdownMenuItem(text = { Text("Перезапустить и выполнить всё") },
                onClick = { menu = false; session.restart(thenRunAll = true) })
            DropdownMenuItem(text = { Text("Выполнить ячейки выше") }, onClick = { menu = false; session.runAbove() })
            DropdownMenuItem(text = { Text("Очистить весь вывод") }, onClick = { menu = false; session.clearAllOutputs() })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Остановить ядро") }, onClick = { menu = false; session.shutdownKernel() })
        }
    }
}

@Composable
fun CompletionStrip(matches: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Clear, "Закрыть", Modifier.size(18.dp))
            }
            matches.take(60).forEach { m ->
                Box(
                    Modifier.height(40.dp).clickable { onPick(m) }.padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(m, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun HelpPanel(text: String, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 3.dp) {
        Box(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
            SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                AnsiText(text, Modifier.padding(end = 40.dp))
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Default.Clear, "Закрыть")
            }
        }
    }
}

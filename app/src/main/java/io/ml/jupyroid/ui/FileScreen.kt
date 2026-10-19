package io.ml.jupyroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ml.jupyroid.editor.EditOps
import io.ml.jupyroid.notebook.LoadState
import io.ml.jupyroid.output.Csv
import io.ml.jupyroid.output.CsvTable
import io.ml.jupyroid.ui.theme.codeColors
import io.ml.jupyroid.workspace.FileKind
import io.ml.jupyroid.workspace.FileSession

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileScreen(file: FileSession, onRun: (() -> Unit)?, modifier: Modifier = Modifier) {
    when (val state = file.loadState) {
        LoadState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is LoadState.Failed -> Column(
            modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Не удалось открыть ${file.name}", style = MaterialTheme.typography.titleMedium)
            Text(state.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
            Button(onClick = file::load) { Text("Повторить") }
        }
        LoadState.Ready -> Column(modifier.fillMaxSize()) {
            FileToolbar(file, onRun)
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    file.preview && file.kind == FileKind.Markdown -> SelectionContainer {
                        MarkdownView(
                            file.text.text,
                            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                    file.preview && file.kind == FileKind.Csv -> CsvView(file.text.text)
                    else -> TextEditor(file, onRun)
                }
            }
            if (WindowInsets.isImeVisible && !file.preview && !file.readOnly) {
                CodeExtraKeys(edit = file::edit, onTab = { file.edit(EditOps::indent) }, onRun = onRun)
            }
        }
    }
}

@Composable
private fun FileToolbar(file: FileSession, onRun: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            file.path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (file.readOnly) {
            Spacer(Modifier.width(8.dp))
            Text("только чтение — файл слишком большой", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.weight(1f))
        if (file.kind == FileKind.Markdown || file.kind == FileKind.Csv) {
            TextButton(onClick = { file.preview = !file.preview }) {
                Text(if (file.preview) "Исходник" else if (file.kind == FileKind.Csv) "Таблица" else "Просмотр")
            }
        }
        if (onRun != null) TextButton(onClick = onRun) { Text("▶ Запустить") }
        if (!file.readOnly) {
            TextButton(onClick = { file.save(quiet = false) }) {
                Text(if (file.saving) "Сохраняю…" else if (file.dirty) "● Сохранить" else "Сохранено")
            }
        }
    }
}

@Composable
private fun TextEditor(file: FileSession, onRun: (() -> Unit)?) {
    val focusRequester = remember { FocusRequester() }
    Column(
        Modifier
            .fillMaxSize()
            .background(codeColors.editorBackground)
            .verticalScroll(rememberScrollState()),
    ) {
        CodeEditor(
            value = file.text,
            onValueChange = file::onTextChange,
            highlight = file.kind == FileKind.Python,
            focusRequester = focusRequester,
            onFocused = {},
            onKey = { handleFileKey(file, onRun, it) },
            readOnly = file.readOnly,
        )
        Spacer(Modifier.height(200.dp))
    }
}

private fun handleFileKey(file: FileSession, onRun: (() -> Unit)?, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val ctrl = e.isCtrlPressed || e.isMetaPressed
    return when {
        e.key == Key.S && ctrl -> { file.save(quiet = false); true }
        e.key == Key.Tab && e.isShiftPressed -> { file.edit(EditOps::dedent); true }
        e.key == Key.Tab && !ctrl -> { file.edit(EditOps::indent); true }
        e.key == Key.Slash && ctrl && file.kind == FileKind.Python -> { file.edit(EditOps::toggleComment); true }
        e.key == Key.Enter && ctrl && onRun != null -> { onRun(); true }
        else -> false
    }
}

private const val MAX_TABLE_ROWS = 2000

@Composable
private fun CsvView(text: String) {
    val table = remember(text) { Csv.parse(text, maxRows = MAX_TABLE_ROWS + 1) }
    if (table.rows.isEmpty()) {
        Text("Пустой файл", Modifier.padding(16.dp))
        return
    }
    val widths = remember(table) { columnWidths(table) }
    val header = table.rows.first()
    val body = table.rows.drop(1).take(MAX_TABLE_ROWS)
    val hScroll = rememberScrollState()
    SelectionContainer {
        Column(Modifier.fillMaxSize()) {
            Text(
                "${body.size}${if (table.truncated || table.rows.size > MAX_TABLE_ROWS) "+" else ""} строк · " +
                    "${table.columnCount} столбцов · разделитель «${if (table.delimiter == '\t') "Tab" else table.delimiter}»",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            CsvRow(header, widths, Modifier.horizontalScroll(hScroll), bold = true)
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
                itemsIndexed(body) { i, row ->
                    CsvRow(
                        row, widths,
                        Modifier
                            .horizontalScroll(hScroll)
                            .background(if (i % 2 == 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface),
                    )
                }
            }
        }
    }
}

@Composable
private fun CsvRow(cells: List<String>, widths: List<Int>, modifier: Modifier, bold: Boolean = false) {
    Row(modifier.padding(horizontal = 4.dp)) {
        widths.forEachIndexed { c, w ->
            Text(
                cells.getOrElse(c) { "" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (bold) FontWeight.SemiBold else null,
                style = CodeTextStyle.copy(fontSize = 13.sp),
                modifier = Modifier.width(w.dp).padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
    }
}

/** Column widths from the longest value in the first rows, clamped to something readable. */
private fun columnWidths(table: CsvTable): List<Int> = (0 until table.columnCount).map { c ->
    val longest = table.rows.take(200).maxOfOrNull { it.getOrNull(c)?.length ?: 0 } ?: 0
    (longest.coerceIn(3, 40) * 8.5f + 16).toInt()
}

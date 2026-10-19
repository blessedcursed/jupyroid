package io.ml.jupyroid.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import io.ml.jupyroid.data.JupyterApi
import io.ml.jupyroid.editor.EditOps
import io.ml.jupyroid.notebook.LoadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class FileKind { Python, Markdown, Csv, Text }

/** A plain text file opened from the server's file browser. */
class FileSession(
    val path: String,
    private val api: JupyterApi,
    parentScope: CoroutineScope,
) : WorkspaceTab {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    val name: String get() = path.substringAfterLast('/')
    override val id: String get() = path
    override val title: String get() = name
    override val persistPath: String get() = path

    val kind: FileKind = kindOf(name)

    var loadState by mutableStateOf<LoadState>(LoadState.Loading)
        private set
    var text by mutableStateOf(TextFieldValue(""))
        private set
    override var dirty by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    override var message by mutableStateOf<String?>(null)

    /** Markdown and CSV open rendered; the toggle switches to the raw editor. */
    var preview by mutableStateOf(kind == FileKind.Markdown || kind == FileKind.Csv)

    /** Huge files open read-only: a Compose text field with megabytes of text is unusable. */
    var readOnly by mutableStateOf(false)
        private set

    fun load() {
        loadState = LoadState.Loading
        scope.launch {
            try {
                val content = api.getTextFile(path)
                readOnly = content.length > MAX_EDITABLE_CHARS
                text = TextFieldValue(content)
                dirty = false
                loadState = LoadState.Ready
            } catch (e: Exception) {
                loadState = LoadState.Failed(e.message ?: e.toString())
            }
        }
        scope.launch {
            while (isActive) {
                delay(AUTOSAVE_MS)
                if (dirty && !saving) saveNow(quiet = true)
            }
        }
    }

    fun onTextChange(value: TextFieldValue) {
        if (readOnly) return
        val newValue = if (kind == FileKind.Python) EditOps.autoIndent(text, value) else value
        if (newValue.text != text.text) dirty = true
        text = newValue
    }

    fun edit(op: (TextFieldValue) -> TextFieldValue) = onTextChange(op(text))

    override fun save(quiet: Boolean) {
        if (loadState != LoadState.Ready || readOnly) return
        scope.launch { saveNow(quiet) }
    }

    override suspend fun saveNow(quiet: Boolean) {
        if (saving || readOnly || loadState != LoadState.Ready) return
        saving = true
        val snapshot = text.text
        try {
            api.saveTextFile(path, snapshot)
            if (text.text == snapshot) dirty = false
            if (!quiet) message = "Сохранено"
        } catch (e: Exception) {
            message = "Не удалось сохранить: ${e.message}"
        } finally {
            saving = false
        }
    }

    override fun dispose() = scope.cancel()

    companion object {
        private const val AUTOSAVE_MS = 30_000L
        const val MAX_EDITABLE_CHARS = 300_000

        private val TEXT_EXTENSIONS = setOf(
            "py", "txt", "md", "markdown", "csv", "tsv", "json", "yaml", "yml", "toml", "cfg", "ini",
            "sh", "bash", "log", "rst", "sql", "r", "html", "css", "js", "xml", "env", "gitignore",
        )

        fun kindOf(name: String): FileKind = when (name.substringAfterLast('.', "").lowercase()) {
            "py" -> FileKind.Python
            "md", "markdown" -> FileKind.Markdown
            "csv", "tsv" -> FileKind.Csv
            else -> FileKind.Text
        }

        /** Whether the file browser should offer to open it as text (files without an extension too). */
        fun isTextFile(name: String): Boolean {
            val ext = name.substringAfterLast('.', "")
            return ext.isEmpty() || ext.lowercase() in TEXT_EXTENSIONS || name.startsWith(".")
        }
    }
}

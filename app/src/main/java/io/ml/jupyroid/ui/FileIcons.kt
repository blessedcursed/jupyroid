package io.ml.jupyroid.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.ml.jupyroid.R

/** File-type icons (Material Symbols Rounded), tinted per type the way IDE file trees do. */
private data class FileIconSpec(@DrawableRes val icon: Int, val tint: Color?)

private val FOLDER = FileIconSpec(R.drawable.ic_file_folder, Color(0xFFD9A441))
private val NOTEBOOK = FileIconSpec(R.drawable.ic_file_notebook, Color(0xFFF37726)) // Jupyter orange
private val PYTHON = FileIconSpec(R.drawable.ic_file_python, Color(0xFF3B7BBF))
private val MARKDOWN = FileIconSpec(R.drawable.ic_file_markdown, Color(0xFF519ABA))
private val TABLE = FileIconSpec(R.drawable.ic_file_table, Color(0xFF3FA65A))
private val DATA = FileIconSpec(R.drawable.ic_file_data, Color(0xFFC39A1C))
private val IMAGE = FileIconSpec(R.drawable.ic_file_image, Color(0xFF9B6BD3))
private val TEXT = FileIconSpec(R.drawable.ic_file_text, null)
private val OTHER = FileIconSpec(R.drawable.ic_file_other, null)

private fun specFor(name: String, isDirectory: Boolean): FileIconSpec {
    if (isDirectory) return FOLDER
    return when (name.substringAfterLast('.', "").lowercase()) {
        "ipynb" -> NOTEBOOK
        "py", "pyi", "pyx" -> PYTHON
        "md", "markdown", "rst" -> MARKDOWN
        "csv", "tsv", "xlsx", "xls", "parquet" -> TABLE
        "json", "yaml", "yml", "toml", "cfg", "ini", "xml", "env" -> DATA
        "png", "jpg", "jpeg", "gif", "svg", "webp", "bmp" -> IMAGE
        "txt", "log", "sh", "bash", "sql", "html", "css", "js", "r" -> TEXT
        "" -> TEXT
        else -> OTHER
    }
}

@Composable
fun FileIcon(name: String, isDirectory: Boolean = false, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    val spec = specFor(name, isDirectory)
    Icon(
        painter = painterResource(spec.icon),
        contentDescription = null,
        tint = spec.tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(size),
    )
}

@Composable
fun TerminalIcon(size: Dp = 22.dp, tint: Color = MaterialTheme.colorScheme.onSurface, modifier: Modifier = Modifier) {
    Icon(painterResource(R.drawable.ic_terminal), contentDescription = "Терминал", tint = tint, modifier = modifier.size(size))
}

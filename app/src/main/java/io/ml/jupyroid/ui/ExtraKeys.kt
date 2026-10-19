package io.ml.jupyroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ml.jupyroid.editor.EditOps

/** A key in an extra-keys row; [active] highlights sticky modifiers. */
data class ExtraKey(val label: String, val active: Boolean = false, val accent: Boolean = false, val action: () -> Unit)

/** Shown above the soft keyboard: keys that are painful or missing on a phone/tablet IME. */
@Composable
fun ExtraKeysRow(keys: List<ExtraKey>) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState())) {
            keys.forEach { key ->
                Box(
                    Modifier
                        .widthIn(min = 42.dp)
                        .height(44.dp)
                        .background(if (key.active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable(onClick = key.action)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        key.label,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        color = if (key.accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Extra keys for code editing (notebook cells, text files). */
@Composable
fun CodeExtraKeys(
    edit: ((TextFieldValue) -> TextFieldValue) -> Unit,
    onTab: () -> Unit,
    onRun: (() -> Unit)? = null,
    onHelp: (() -> Unit)? = null,
    onParamInfo: (() -> Unit)? = null,
    onUp: () -> Unit = { edit { EditOps.move(it, EditOps.Move.Up) } },
    onDown: () -> Unit = { edit { EditOps.move(it, EditOps.Move.Down) } },
) {
    fun ins(s: String) = ExtraKey(s) { edit { EditOps.insert(it, s) } }
    fun pair(c: Char) = ExtraKey(c.toString()) { edit { EditOps.insertPaired(it, c) } }
    fun move(label: String, m: EditOps.Move) = ExtraKey(label) { edit { EditOps.move(it, m) } }

    ExtraKeysRow(
        listOfNotNull(
            onRun?.let { ExtraKey("▶", accent = true, action = it) },
            ExtraKey("Tab", action = onTab),
            ExtraKey("⇤") { edit(EditOps::dedent) },
            move("←", EditOps.Move.Left),
            move("→", EditOps.Move.Right),
            ExtraKey("↑", action = onUp),
            ExtraKey("↓", action = onDown),
            pair('('), ins(")"),
            pair('['), ins("]"),
            pair('{'), ins("}"),
            ins(":"), ins("="), ins("."), ins(","), ins("_"),
            pair('\''), pair('"'),
            ExtraKey("#") { edit(EditOps::toggleComment) },
            move("Home", EditOps.Move.Home), move("End", EditOps.Move.End),
            onParamInfo?.let { ExtraKey("f(x)", action = it) },
            onHelp?.let { ExtraKey("?", action = it) },
        ),
    )
}

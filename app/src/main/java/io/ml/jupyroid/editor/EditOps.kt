package io.ml.jupyroid.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Pure text-editing operations on a code cell; no Android dependencies so they're unit-testable. */
object EditOps {
    const val INDENT = "    "

    private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')

    fun insert(value: TextFieldValue, s: String, cursorOffset: Int = s.length): TextFieldValue {
        val start = value.selection.min
        val text = value.text.replaceRange(start, value.selection.max, s)
        return TextFieldValue(text, TextRange(start + cursorOffset))
    }

    /**
     * Inserts an opening bracket/quote from the extra-keys bar. Pairs it with the closing one when
     * nothing word-like follows the cursor; wraps the selection if there is one.
     */
    fun insertPaired(value: TextFieldValue, open: Char): TextFieldValue {
        val close = PAIRS[open] ?: return insert(value, open.toString())
        val sel = value.selection
        if (!sel.collapsed) {
            val inner = value.text.substring(sel.min, sel.max)
            return insert(value, "$open$inner$close", inner.length + 1)
        }
        // Typing a closing quote over an auto-inserted one just steps past it.
        if (open == close && value.text.getOrNull(sel.start) == close) {
            return value.copy(selection = TextRange(sel.start + 1))
        }
        val next = value.text.getOrNull(sel.start)
        return if (next == null || next.isWhitespace() || next in ")]}:,") {
            insert(value, "$open$close", 1)
        } else {
            insert(value, open.toString())
        }
    }

    /**
     * Called with the field's value before and after an edit. When the edit inserted a single
     * newline, carries the previous line's indentation over (one level deeper after a `:`).
     */
    fun autoIndent(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
        if (!new.selection.collapsed) return new
        val cursor = new.selection.start
        if (new.text.length != old.text.length - (old.selection.max - old.selection.min) + 1) return new
        if (cursor == 0 || new.text[cursor - 1] != '\n') return new
        if (old.text.substring(0, old.selection.min) != new.text.substring(0, cursor - 1)) return new

        val lineStart = new.text.lastIndexOf('\n', cursor - 2) + 1
        val prevLine = new.text.substring(lineStart, cursor - 1)
        var indent = prevLine.takeWhile { it == ' ' || it == '\t' }
        val code = prevLine.substringBefore('#').trimEnd()
        if (code.endsWith(':')) indent += INDENT
        if (indent.isEmpty()) return new
        return insert(new, indent)
    }

    /** Tab: indents selected lines, or inserts spaces up to the next indent stop. */
    fun indent(value: TextFieldValue): TextFieldValue {
        if (spansLines(value)) return mapSelectedLines(value) { INDENT + it }
        val col = value.selection.min - lineStart(value.text, value.selection.min)
        return insert(value, " ".repeat(INDENT.length - col % INDENT.length))
    }

    /** Shift+Tab: removes one indent level from the current or selected lines. */
    fun dedent(value: TextFieldValue): TextFieldValue = mapSelectedLines(value) { line ->
        val n = line.takeWhile { it == ' ' }.length.coerceAtMost(INDENT.length)
        if (n == 0 && line.startsWith('\t')) line.substring(1) else line.substring(n)
    }

    /** Ctrl+/: comments the lines out, or back in if all of them already are. */
    fun toggleComment(value: TextFieldValue): TextFieldValue {
        val (start, end) = selectedLineRange(value)
        val lines = value.text.substring(start, end).split('\n')
        val uncomment = lines.filter { it.isNotBlank() }.all { it.trimStart().startsWith("#") }
        return mapSelectedLines(value) { line ->
            when {
                line.isBlank() -> line
                uncomment -> {
                    val i = line.indexOf('#')
                    line.removeRange(i, if (line.getOrNull(i + 1) == ' ') i + 2 else i + 1)
                }
                else -> {
                    val i = line.indexOfFirst { !it.isWhitespace() }
                    line.substring(0, i) + "# " + line.substring(i)
                }
            }
        }
    }

    /** Backspace that removes a whole indent level when the cursor sits in leading whitespace. */
    fun backspace(value: TextFieldValue): TextFieldValue {
        val sel = value.selection
        if (!sel.collapsed) return insert(value, "")
        if (sel.start == 0) return value
        val ls = lineStart(value.text, sel.start)
        val before = value.text.substring(ls, sel.start)
        val n = if (before.isNotEmpty() && before.all { it == ' ' }) {
            (before.length % INDENT.length).let { if (it == 0) INDENT.length else it }
        } else {
            1
        }
        return TextFieldValue(value.text.removeRange(sel.start - n, sel.start), TextRange(sel.start - n))
    }

    enum class Move { Left, Right, Up, Down, Home, End }

    fun move(value: TextFieldValue, move: Move): TextFieldValue {
        val text = value.text
        val pos = value.selection.end
        val ls = lineStart(text, pos)
        val col = pos - ls
        val target = when (move) {
            Move.Left -> (pos - 1).coerceAtLeast(0)
            Move.Right -> (pos + 1).coerceAtMost(text.length)
            Move.Home -> {
                // Smart home: first jump to the indentation, then to column 0.
                val firstNonBlank = ls + text.substring(ls, lineEnd(text, pos)).indexOfFirst { !it.isWhitespace() }
                if (firstNonBlank >= ls && pos != firstNonBlank) firstNonBlank else ls
            }
            Move.End -> lineEnd(text, pos)
            Move.Up -> if (ls == 0) 0 else {
                val prevStart = lineStart(text, ls - 1)
                prevStart + col.coerceAtMost(ls - 1 - prevStart)
            }
            Move.Down -> {
                val le = lineEnd(text, pos)
                if (le == text.length) text.length else {
                    val nextStart = le + 1
                    nextStart + col.coerceAtMost(lineEnd(text, nextStart) - nextStart)
                }
            }
        }
        return value.copy(selection = TextRange(target))
    }

    fun isOnFirstLine(value: TextFieldValue) = value.text.lastIndexOf('\n', value.selection.start - 1) < 0
    fun isOnLastLine(value: TextFieldValue) = value.text.indexOf('\n', value.selection.end) < 0

    /** True when the char before the cursor makes a completion request worthwhile. */
    fun wantsCompletion(value: TextFieldValue): Boolean {
        if (!value.selection.collapsed) return false
        val c = value.text.getOrNull(value.selection.start - 1) ?: return false
        return c.isLetterOrDigit() || c == '_' || c == '.' || c == '/' || c == '"' || c == '\''
    }

    fun replace(value: TextFieldValue, start: Int, end: Int, s: String): TextFieldValue =
        TextFieldValue(value.text.replaceRange(start, end, s), TextRange(start + s.length))

    private fun lineStart(text: String, pos: Int) = text.lastIndexOf('\n', pos - 1) + 1
    private fun lineEnd(text: String, pos: Int) = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
    private fun spansLines(v: TextFieldValue) = '\n' in v.text.substring(v.selection.min, v.selection.max)

    private fun selectedLineRange(v: TextFieldValue): Pair<Int, Int> =
        lineStart(v.text, v.selection.min) to lineEnd(v.text, v.selection.max)

    /** Applies [f] to every line touched by the selection, keeping the selection on the same text. */
    private fun mapSelectedLines(value: TextFieldValue, f: (String) -> String): TextFieldValue {
        val (start, end) = selectedLineRange(value)
        val oldLines = value.text.substring(start, end).split('\n')
        val newLines = oldLines.map(f)
        val text = value.text.substring(0, start) + newLines.joinToString("\n") + value.text.substring(end)

        // Keep each selection end on its line, shifted by how much that line grew or shrank.
        fun shift(pos: Int): Int {
            val lineIdx = value.text.substring(start, pos).count { it == '\n' }.coerceAtMost(oldLines.lastIndex)
            val oldLineStart = start + oldLines.take(lineIdx).sumOf { it.length + 1 }
            val newLineStart = start + newLines.take(lineIdx).sumOf { it.length + 1 }
            val delta = newLines[lineIdx].length - oldLines[lineIdx].length
            return newLineStart + (pos - oldLineStart + delta).coerceIn(0, newLines[lineIdx].length)
        }
        val sel = value.selection
        return TextFieldValue(text, TextRange(shift(sel.start), shift(sel.end)))
    }
}

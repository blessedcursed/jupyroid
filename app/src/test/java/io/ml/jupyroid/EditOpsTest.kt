package io.ml.jupyroid

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.ml.jupyroid.editor.EditOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditOpsTest {
    /** `|` marks the cursor. */
    private fun v(s: String) = TextFieldValue(s.replace("|", ""), TextRange(s.indexOf('|')))
    private fun TextFieldValue.show() = text.substring(0, selection.start) + "|" + text.substring(selection.start)

    private fun typeNewline(before: String): String {
        val old = v(before)
        return EditOps.autoIndent(old, EditOps.insert(old, "\n")).show()
    }

    @Test
    fun autoIndentKeepsIndentation() = assertEquals("    x = 1\n    |", typeNewline("    x = 1|"))

    @Test
    fun autoIndentAfterColon() = assertEquals("def f():\n    |", typeNewline("def f():|"))

    @Test
    fun autoIndentIgnoresColonInComment() = assertEquals("x = 1  # note:\n|", typeNewline("x = 1  # note:|"))

    @Test
    fun autoIndentOnlyForSingleNewline() {
        val old = v("a|")
        val pasted = EditOps.insert(old, "\nb")
        assertEquals(pasted, EditOps.autoIndent(old, pasted))
    }

    @Test
    fun tabInsertsToNextStop() {
        assertEquals("    |x", EditOps.indent(v("|x")).show())
        assertEquals("ab  |", EditOps.indent(v("ab|")).show())
    }

    @Test
    fun indentAndDedentSelectedLines() {
        val sel = TextFieldValue("a\nb\nc", TextRange(0, 3))
        val indented = EditOps.indent(sel)
        assertEquals("    a\n    b\nc", indented.text)
        assertEquals("a\nb\nc", EditOps.dedent(indented).text)
    }

    @Test
    fun toggleComment() {
        val once = EditOps.toggleComment(v("    x = 1|"))
        assertEquals("    # x = 1|", once.show())
        assertEquals("    x = 1|", EditOps.toggleComment(once).show())
    }

    @Test
    fun backspaceRemovesIndentLevel() {
        assertEquals("if x:\n    |", EditOps.backspace(v("if x:\n        |")).show())
        assertEquals("ab|", EditOps.backspace(v("abc|")).show())
    }

    @Test
    fun pairedBrackets() {
        assertEquals("f(|)", EditOps.insertPaired(v("f|"), '(').show())
        assertEquals("(|x", EditOps.insertPaired(v("|x"), '(').show())
        assertEquals("'a'|", EditOps.insertPaired(v("'a|'"), '\'').show())
    }

    @Test
    fun cursorMovement() {
        assertEquals("ab\nc|d", EditOps.move(v("a|b\ncd"), EditOps.Move.Down).show())
        assertEquals("a|b\ncd", EditOps.move(v("ab\nc|d"), EditOps.Move.Up).show())
        assertEquals("ab|\ncd", EditOps.move(v("|ab\ncd"), EditOps.Move.End).show())
        assertEquals("    |x", EditOps.move(v("    x|"), EditOps.Move.Home).show())
        assertEquals("|    x", EditOps.move(v("    |x"), EditOps.Move.Home).show())
    }

    @Test
    fun lineEdges() {
        assertTrue(EditOps.isOnFirstLine(v("a|b\nc")))
        assertFalse(EditOps.isOnFirstLine(v("ab\n|c")))
        assertTrue(EditOps.isOnLastLine(v("ab\n|c")))
        assertFalse(EditOps.isOnLastLine(v("a|b\nc")))
    }

    @Test
    fun completionTrigger() {
        assertTrue(EditOps.wantsCompletion(v("np.|")))
        assertTrue(EditOps.wantsCompletion(v("pri|")))
        assertFalse(EditOps.wantsCompletion(v("x = |")))
        assertFalse(EditOps.wantsCompletion(v("|")))
    }
}

package io.ml.jupyroid

import io.ml.jupyroid.editor.PythonLexer
import io.ml.jupyroid.editor.TokenType
import io.ml.jupyroid.output.Ansi
import io.ml.jupyroid.output.AnsiColor
import io.ml.jupyroid.output.Markdown
import io.ml.jupyroid.output.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Test

class LexerAnsiMarkdownTest {
    private fun tokens(code: String) = PythonLexer.tokenize(code).map { code.substring(it.start, it.end) to it.type }

    @Test
    fun lexesBasics() {
        assertEquals(
            listOf(
                "def" to TokenType.Keyword, "f" to TokenType.Function, "return" to TokenType.Keyword,
                "1.5" to TokenType.Number, "# done" to TokenType.Comment,
            ),
            tokens("def f(x):\n    return x + 1.5  # done"),
        )
    }

    @Test
    fun lexesStrings() {
        assertEquals(listOf("f\"a{b}\"" to TokenType.Str), tokens("f\"a{b}\""))
        assertEquals(listOf("'''x\ny'''" to TokenType.Str, "None" to TokenType.Constant), tokens("'''x\ny''' None"))
        assertEquals(listOf("'it\\'s'" to TokenType.Str), tokens("'it\\'s'"))
        // Unterminated single-quoted strings stop at the end of the line.
        assertEquals(listOf("'abc" to TokenType.Str, "1" to TokenType.Number), tokens("'abc\n1"))
    }

    @Test
    fun lexesMagicsDecoratorsBuiltins() {
        assertEquals(listOf("%timeit" to TokenType.Magic, "len" to TokenType.Builtin), tokens("%timeit len(x)"))
        assertEquals(
            listOf("@dataclass" to TokenType.Decorator, "class" to TokenType.Keyword, "A" to TokenType.ClassName),
            tokens("@dataclass\nclass A: pass").take(3),
        )
        // Attribute access isn't the builtin.
        assertEquals(emptyList<Pair<String, TokenType>>(), tokens("df.max"))
    }

    @Test
    fun parsesAnsi() {
        val esc = "\u001B"
        val spans = Ansi.parse("a$esc[0;31mred$esc[1;32mgreen$esc[0m b$esc[38;5;208mo$esc[38;2;1;2;3mr")
        assertEquals(listOf("a", "red", "green", " b", "o", "r"), spans.map { it.text })
        assertEquals(AnsiColor.Indexed(1), spans[1].fg)
        assertEquals(true, spans[2].bold)
        assertEquals(null, spans[3].fg)
        assertEquals(AnsiColor.Rgb(0xFF8700), spans[4].fg)
        assertEquals(AnsiColor.Rgb(0x010203), spans[5].fg)
        assertEquals("plain", Ansi.strip("$esc[2Kplain"))
    }

    @Test
    fun parsesMarkdownBlocks() {
        val source = listOf(
            "# Title",
            "Some *text*",
            "continues here.",
            "",
            "- one",
            "  - nested",
            "1. first",
            "",
            "```python",
            "x = 1",
            "```",
            "| a | b |",
            "|---|---|",
            "| 1 | 2 |",
            "",
            "> quote",
            "---",
            "$$ x^2 $$",
        ).joinToString("\n")
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Title"),
                MdBlock.Paragraph("Some *text* continues here."),
                MdBlock.ListItem(false, "-", 0, "one"),
                MdBlock.ListItem(false, "-", 1, "nested"),
                MdBlock.ListItem(true, "1.", 0, "first"),
                MdBlock.Code("python", "x = 1"),
                MdBlock.Table(listOf("a", "b"), listOf(listOf("1", "2"))),
                MdBlock.Quote("quote"),
                MdBlock.Rule,
                MdBlock.Math("x^2"),
            ),
            Markdown.parse(source),
        )
    }
}

package io.ml.jupyroid.editor

enum class TokenType { Keyword, Builtin, Constant, Str, Comment, Number, Function, ClassName, Decorator, Magic, Self }

data class Token(val start: Int, val end: Int, val type: TokenType)

/**
 * A small single-pass Python tokenizer for highlighting. It's forgiving by design: an
 * unterminated string just runs to the end of the line (or the cell, if triple-quoted)
 * instead of failing, since the text is usually mid-edit.
 */
object PythonLexer {
    private val KEYWORDS = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
        "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda",
        "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield",
        "match", "case",
    )
    private val CONSTANTS = setOf("True", "False", "None", "Ellipsis", "NotImplemented", "__name__", "__file__")
    private val BUILTINS = setOf(
        "abs", "all", "any", "bin", "bool", "breakpoint", "bytearray", "bytes", "callable", "chr",
        "classmethod", "compile", "complex", "delattr", "dict", "dir", "divmod", "enumerate", "eval",
        "exec", "filter", "float", "format", "frozenset", "getattr", "globals", "hasattr", "hash", "help",
        "hex", "id", "input", "int", "isinstance", "issubclass", "iter", "len", "list", "locals", "map",
        "max", "memoryview", "min", "next", "object", "oct", "open", "ord", "pow", "print", "property",
        "range", "repr", "reversed", "round", "set", "setattr", "slice", "sorted", "staticmethod", "str",
        "sum", "super", "tuple", "type", "vars", "zip", "display",
        "Exception", "BaseException", "ValueError", "TypeError", "KeyError", "IndexError",
        "AttributeError", "RuntimeError", "StopIteration", "NotImplementedError", "ImportError",
        "FileNotFoundError", "OSError", "ZeroDivisionError", "AssertionError", "KeyboardInterrupt",
    )
    private val STRING_PREFIX = Regex("[rRbBuUfF]{1,2}")

    fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        var atLineStart = true
        var expectName: TokenType? = null // set after `def` / `class`
        val n = text.length

        while (i < n) {
            val c = text[i]
            when {
                c == '\n' -> { atLineStart = true; i++; continue }
                c == ' ' || c == '\t' -> { i++; continue }
                c == '#' -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                    tokens += Token(i, end, TokenType.Comment)
                    i = end
                }
                atLineStart && (c == '%' || c == '!') -> {
                    // IPython magics / shell escapes; highlight just the command word.
                    var end = i + 1
                    while (end < n && (text[end] == '%' || text[end].isLetterOrDigit() || text[end] == '_')) end++
                    tokens += Token(i, end, TokenType.Magic)
                    i = end
                }
                c == '@' && atLineStart -> {
                    var end = i + 1
                    while (end < n && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '.')) end++
                    tokens += Token(i, end, TokenType.Decorator)
                    i = end
                }
                c == '"' || c == '\'' -> i = string(text, i, i, tokens)
                c.isDigit() || (c == '.' && text.getOrNull(i + 1)?.isDigit() == true) -> {
                    var end = i + 1
                    while (end < n && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '.' ||
                            ((text[end] == '+' || text[end] == '-') && text[end - 1] in "eE" && !text.startsWith("0x", i)))
                    ) end++
                    tokens += Token(i, end, TokenType.Number)
                    i = end
                }
                c.isLetter() || c == '_' -> {
                    var end = i + 1
                    while (end < n && (text[end].isLetterOrDigit() || text[end] == '_')) end++
                    val word = text.substring(i, end)
                    val quote = text.getOrNull(end)
                    if ((quote == '"' || quote == '\'') && STRING_PREFIX.matches(word)) {
                        i = string(text, i, end, tokens)
                    } else {
                        val type = when {
                            expectName != null -> expectName
                            word in KEYWORDS -> TokenType.Keyword
                            word in CONSTANTS -> TokenType.Constant
                            word == "self" || word == "cls" -> TokenType.Self
                            // Builtins only when not used as an attribute (`df.max` isn't the builtin).
                            word in BUILTINS && text.getOrNull(i - 1) != '.' -> TokenType.Builtin
                            else -> null
                        }
                        expectName = when (word) {
                            "def" -> TokenType.Function
                            "class" -> TokenType.ClassName
                            else -> null
                        }
                        if (type != null) tokens += Token(i, end, type)
                        i = end
                    }
                }
                else -> i++
            }
            atLineStart = false
        }
        return tokens
    }

    /** Scans a string literal whose prefix starts at [start] and quote at [quoteAt]; returns the end index. */
    private fun string(text: String, start: Int, quoteAt: Int, tokens: MutableList<Token>): Int {
        val q = text[quoteAt]
        val triple = text.startsWith("$q$q$q", quoteAt)
        var i = quoteAt + if (triple) 3 else 1
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '\\' -> i += 2
                triple && text.startsWith("$q$q$q", i) -> { i += 3; break }
                !triple && c == q -> { i++; break }
                !triple && c == '\n' -> break
                else -> i++
            }
        }
        val end = i.coerceAtMost(n)
        tokens += Token(start, end, TokenType.Str)
        return end
    }
}

package io.ml.jupyroid.editor

import io.ml.jupyroid.output.Ansi

/**
 * Where the cursor is inside a call: `pd.read_csv("a.csv", sep=|` →
 * callee `pd.read_csv` ending at [calleeEnd], open paren at [openParen], argument #1, keyword `sep`.
 */
data class CallContext(
    val callee: String,
    val calleeEnd: Int,
    val openParen: Int,
    val argIndex: Int,
    /** Set when the current argument is being passed by name (`name=`). */
    val keyword: String?,
)

object CallContexts {
    private val NOT_CALLS = setOf(
        "if", "elif", "while", "for", "in", "and", "or", "not", "return", "yield", "with", "assert",
        "lambda", "except", "del", "is", "else", "import", "from", "raise", "await",
    )
    private val DECLARATION = Regex("(^|\\W)(def|class)$")
    private val KEYWORD_ARG = Regex("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=(?!=)")

    private class Bracket(val char: Char, val index: Int) {
        var commas = 0
        var lastArgStart = index + 1
    }

    /** The innermost call whose parentheses contain [cursor], or null outside any call. */
    fun find(text: String, cursor: Int): CallContext? {
        val end = cursor.coerceIn(0, text.length)
        val stack = ArrayList<Bracket>()
        var i = 0
        // A cursor inside a string or comment (typing `f("data.csv` …) still belongs to the call
        // around it, so scanning just stops there instead of giving up.
        scan@ while (i < end) {
            val c = text[i]
            when {
                c == '#' -> {
                    val nl = text.indexOf('\n', i)
                    if (nl < 0 || nl >= end) break@scan
                    i = nl
                }
                c == '"' || c == '\'' -> {
                    val close = skipString(text, i)
                    if (close < 0 || close > end) break@scan
                    i = close - 1
                }
                c == '(' || c == '[' || c == '{' -> stack += Bracket(c, i)
                c == ')' || c == ']' || c == '}' -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                c == ',' -> stack.lastOrNull()?.let { it.commas++; it.lastArgStart = i + 1 }
            }
            i++
        }
        // `f([1, 2|` still shows f's parameters, so look through inner [ and {.
        val call = stack.lastOrNull { it.char == '(' } ?: return null
        val calleeEnd = run {
            var j = call.index
            while (j > 0 && text[j - 1].let { it == ' ' || it == '\t' }) j--
            j
        }
        var start = calleeEnd
        while (start > 0 && text[start - 1].let { it.isLetterOrDigit() || it == '_' || it == '.' }) start--
        val callee = text.substring(start, calleeEnd)
        if (callee.isEmpty() || callee.first().isDigit() || callee.startsWith(".") || callee in NOT_CALLS) return null
        // `def f(` / `class A(` declare rather than call.
        val before = text.substring(0, start).trimEnd()
        if (DECLARATION.containsMatchIn(before)) return null

        val isInnermost = stack.last() === call
        val keyword = if (isInnermost) {
            KEYWORD_ARG.find(text.substring(call.lastArgStart, end))?.groupValues?.get(1)
        } else {
            null
        }
        return CallContext(callee, calleeEnd, call.index, call.commas, keyword)
    }

    /** Index just past the closing quote of the literal starting at [start], or -1 if it isn't closed. */
    private fun skipString(text: String, start: Int): Int {
        val q = text[start]
        val triple = text.startsWith("$q$q$q", start)
        var i = start + if (triple) 3 else 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> i++
                triple && text.startsWith("$q$q$q", i) -> return i + 3
                !triple && c == q -> return i + 1
                !triple && c == '\n' -> return -1
            }
            i++
        }
        return -1
    }
}

/** A callable's signature as shown in the parameter-info popup. */
data class Signature(val name: String, val params: List<String>, val returns: String?) {
    /** Which entry of [params] the argument at [ctx] binds to, or null if none does. */
    fun activeParam(ctx: CallContext): Int? {
        val names = params.map { paramName(it) }
        ctx.keyword?.let { kw ->
            val i = names.indexOf(kw)
            if (i >= 0) return i
            return params.indexOfFirst { it.startsWith("**") }.takeIf { it >= 0 }
        }
        var positional = 0
        for ((i, p) in params.withIndex()) {
            when {
                p == "/" -> continue
                p == "*" || p.startsWith("**") -> return null // keyword-only from here on
                p.startsWith("*") -> return i // *args swallows the rest
                positional == ctx.argIndex -> return i
                else -> positional++
            }
        }
        return null
    }

    companion object {
        fun paramName(param: String): String =
            param.trimStart('*').substringBefore(':').substringBefore('=').trim()

        /** `sep: 'str | None' = <no_default>` → `sep=<no_default>`; annotations are noise when skimming. */
        fun compact(param: String): String {
            if (param == "*" || param == "/") return param
            val stars = param.takeWhile { it == '*' }
            val default = topLevelDefault(param)
            return stars + paramName(param) + (default?.let { "=$it" } ?: "")
        }

        /** The default value after the top-level `=` (outside quotes and brackets), if any. */
        private fun topLevelDefault(param: String): String? {
            var depth = 0
            var quote: Char? = null
            for ((i, c) in param.withIndex()) {
                when {
                    quote != null -> if (c == quote) quote = null
                    c == '\'' || c == '"' -> quote = c
                    c == '(' || c == '[' || c == '{' -> depth++
                    c == ')' || c == ']' || c == '}' -> depth--
                    c == '=' && depth == 0 -> return param.substring(i + 1).trim()
                }
            }
            return null
        }
    }
}

/**
 * Pulls the signature out of an IPython `inspect_reply` (detail level 0), e.g.
 * `Signature: pd.read_csv(filepath_or_buffer, *, sep=..., ...) -> DataFrame`. Classes use
 * `Init signature:`, callable objects `Call signature:`; builtins without one often start
 * their docstring with `name(args)`.
 */
object SignatureParser {
    private val HEADERS = listOf("Signature:", "Init signature:", "Call signature:")
    private val SECTION = Regex("^[A-Z][A-Za-z ]*:")

    fun parse(inspectText: String, callee: String): Signature? {
        val lines = Ansi.strip(inspectText).lines()
        for (header in HEADERS) {
            val section = section(lines, header) ?: continue
            parseCall(section)?.let { return it }
        }
        // Fallback: a docstring whose first line looks like the call.
        val doc = section(lines, "Docstring:") ?: return null
        val short = callee.substringAfterLast('.')
        val first = doc.trim().lineSequence().firstOrNull()?.trim() ?: return null
        return if (first.startsWith("$short(")) parseCall(first) else null
    }

    private fun section(lines: List<String>, header: String): String? {
        val start = lines.indexOfFirst { it.startsWith(header) }
        if (start < 0) return null
        val body = StringBuilder(lines[start].removePrefix(header))
        for (line in lines.drop(start + 1)) {
            if (SECTION.containsMatchIn(line)) break
            body.append('\n').append(line)
        }
        return body.toString()
    }

    /** Parses `name(a, b=1, *args) -> ret` (possibly spread over several lines). */
    fun parseCall(text: String): Signature? {
        val open = text.indexOf('(')
        if (open < 0) return null
        val name = text.substring(0, open).trim().substringAfterLast('.')
        val params = ArrayList<String>()
        var depth = 0
        var quote: Char? = null
        val current = StringBuilder()
        var close = -1
        var i = open + 1
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                current.append(c)
                if (c == '\\' && i + 1 < text.length) current.append(text[++i])
                else if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> { quote = c; current.append(c) }
                '(', '[', '{' -> { depth++; current.append(c) }
                ')', ']', '}' -> {
                    if (depth == 0 && c == ')') { close = i; break }
                    depth--
                    current.append(c)
                }
                ',' -> if (depth == 0) { params += current.toString(); current.clear() } else current.append(c)
                else -> current.append(c)
            }
            i++
        }
        if (close < 0) return null
        params += current.toString()
        val cleaned = params.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
        val returns = text.substring(close + 1).substringAfter("->", "").replace(Regex("\\s+"), " ").trim()
        return Signature(name, cleaned, returns.ifEmpty { null })
    }
}

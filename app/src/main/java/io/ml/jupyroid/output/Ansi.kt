package io.ml.jupyroid.output

/**
 * A run of text with the SGR attributes in effect. [fg]/[bg] are either a palette index
 * 0..15 ([AnsiColor.Indexed]) or a 24-bit RGB value ([AnsiColor.Rgb]).
 */
data class AnsiSpan(val text: String, val fg: AnsiColor? = null, val bg: AnsiColor? = null, val bold: Boolean = false)

sealed interface AnsiColor {
    data class Indexed(val index: Int) : AnsiColor
    data class Rgb(val rgb: Int) : AnsiColor
}

/** Splits text with ANSI escape sequences (IPython tracebacks, colored logs) into styled spans. */
object Ansi {
    private val CSI = Regex("\u001B\\[([0-9;?]*)([A-Za-z])")

    fun parse(text: String): List<AnsiSpan> {
        if ('\u001B' !in text) return listOf(AnsiSpan(text))
        val spans = ArrayList<AnsiSpan>()
        var fg: AnsiColor? = null
        var bg: AnsiColor? = null
        var bold = false
        var last = 0

        fun flush(end: Int) {
            if (end > last) spans += AnsiSpan(text.substring(last, end), fg, bg, bold)
        }

        for (m in CSI.findAll(text)) {
            flush(m.range.first)
            last = m.range.last + 1
            if (m.groupValues[2] != "m") continue // cursor movement etc.: drop
            val codes = m.groupValues[1].split(';').map { it.toIntOrNull() ?: 0 }
            var i = 0
            while (i < codes.size) {
                when (val code = codes[i]) {
                    0 -> { fg = null; bg = null; bold = false }
                    1 -> bold = true
                    22 -> bold = false
                    in 30..37 -> fg = AnsiColor.Indexed(code - 30)
                    in 90..97 -> fg = AnsiColor.Indexed(code - 90 + 8)
                    39 -> fg = null
                    in 40..47 -> bg = AnsiColor.Indexed(code - 40)
                    in 100..107 -> bg = AnsiColor.Indexed(code - 100 + 8)
                    49 -> bg = null
                    38, 48 -> {
                        val (color, used) = extended(codes, i + 1)
                        if (code == 38) fg = color else bg = color
                        i += used
                    }
                }
                i++
            }
        }
        flush(text.length)
        return spans.ifEmpty { listOf(AnsiSpan("")) }
    }

    /** Parses `5;n` or `2;r;g;b` after a 38/48; returns the color and how many codes it consumed. */
    private fun extended(codes: List<Int>, at: Int): Pair<AnsiColor?, Int> = when (codes.getOrNull(at)) {
        5 -> {
            val n = codes.getOrNull(at + 1) ?: 0
            (if (n < 16) AnsiColor.Indexed(n) else AnsiColor.Rgb(xterm256(n))) to 2
        }
        2 -> {
            val r = codes.getOrNull(at + 1) ?: 0
            val g = codes.getOrNull(at + 2) ?: 0
            val b = codes.getOrNull(at + 3) ?: 0
            AnsiColor.Rgb((r shl 16) or (g shl 8) or b) to 4
        }
        else -> null to 0
    }

    private fun xterm256(n: Int): Int {
        if (n >= 232) {
            val v = 8 + (n - 232) * 10
            return (v shl 16) or (v shl 8) or v
        }
        val i = n - 16
        val steps = intArrayOf(0, 95, 135, 175, 215, 255)
        return (steps[i / 36] shl 16) or (steps[(i / 6) % 6] shl 8) or steps[i % 6]
    }

    fun strip(text: String): String = text.replace(CSI, "")
}

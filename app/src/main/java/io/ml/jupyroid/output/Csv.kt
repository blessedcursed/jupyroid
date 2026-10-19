package io.ml.jupyroid.output

/** RFC 4180-ish CSV reading for the table preview: quoted fields, escaped quotes, newlines in quotes. */
object Csv {
    private val CANDIDATES = charArrayOf(',', ';', '\t', '|')

    /** Picks the delimiter that occurs most often (outside quotes) in the first line. */
    fun sniffDelimiter(text: String): Char {
        val counts = IntArray(CANDIDATES.size)
        var quoted = false
        for (c in text) {
            if (c == '"') quoted = !quoted
            if (!quoted && (c == '\n' || c == '\r')) break
            if (!quoted) CANDIDATES.indexOf(c).takeIf { it >= 0 }?.let { counts[it]++ }
        }
        val best = counts.indices.maxBy { counts[it] }
        return if (counts[best] == 0) ',' else CANDIDATES[best]
    }

    /** Parses up to [maxRows] rows; [truncated] tells whether more followed. */
    fun parse(text: String, delimiter: Char = sniffDelimiter(text), maxRows: Int = Int.MAX_VALUE): CsvTable {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        var truncated = false

        fun endField() {
            row.add(field.toString())
            field.clear()
        }

        fun endRow() {
            endField()
            rows.add(row)
            row = ArrayList()
        }

        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> field.append(c)
                c == '"' && field.isEmpty() -> quoted = true
                c == delimiter -> endField()
                c == '\r' && text.getOrNull(i + 1) == '\n' -> Unit
                c == '\n' || c == '\r' -> {
                    endRow()
                    if (rows.size >= maxRows) {
                        truncated = text.substring(i + 1).isNotBlank()
                        break
                    }
                }
                else -> field.append(c)
            }
            i++
        }
        if (!truncated && (field.isNotEmpty() || row.isNotEmpty())) endRow()
        return CsvTable(rows, delimiter, truncated)
    }
}

data class CsvTable(val rows: List<List<String>>, val delimiter: Char, val truncated: Boolean) {
    val columnCount: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

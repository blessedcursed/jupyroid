package io.ml.jupyroid.output

/** Block structure of the Markdown subset notebooks typically use. Inline markup stays in the strings. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val language: String, val text: String) : MdBlock
    data class Math(val text: String) : MdBlock
    data class ListItem(val ordered: Boolean, val marker: String, val depth: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

object Markdown {
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val BULLET = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d+[.)])\\s+(.*)$")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

    fun parse(source: String): List<MdBlock> {
        val lines = source.replace("\r\n", "\n").split('\n')
        val blocks = ArrayList<MdBlock>()
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    flushParagraph()
                    val fence = trimmed.take(3)
                    val lang = trimmed.drop(3).trim()
                    val body = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) body += lines[i++]
                    blocks += MdBlock.Code(lang, body.joinToString("\n"))
                }
                trimmed.startsWith("$$") -> {
                    flushParagraph()
                    val rest = trimmed.removePrefix("$$")
                    if (rest.endsWith("$$") && rest.length >= 2) {
                        blocks += MdBlock.Math(rest.removeSuffix("$$").trim())
                    } else {
                        val body = arrayListOf(rest)
                        i++
                        while (i < lines.size && !lines[i].trim().endsWith("$$")) body += lines[i++]
                        if (i < lines.size) body += lines[i].trim().removeSuffix("$$")
                        blocks += MdBlock.Math(body.joinToString("\n").trim())
                    }
                }
                trimmed.isEmpty() -> flushParagraph()
                HEADING.matches(trimmed) -> {
                    flushParagraph()
                    val m = HEADING.find(trimmed)!!
                    blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                RULE.matches(line) -> { flushParagraph(); blocks += MdBlock.Rule }
                trimmed.startsWith(">") -> {
                    flushParagraph()
                    val body = ArrayList<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        body += lines[i].trim().removePrefix(">").trimStart()
                        i++
                    }
                    blocks += MdBlock.Quote(body.joinToString("\n"))
                    continue
                }
                '|' in trimmed && lines.getOrNull(i + 1)?.let { TABLE_SEP.matches(it) && '-' in it } == true -> {
                    flushParagraph()
                    val header = cells(trimmed)
                    val rows = ArrayList<List<String>>()
                    i += 2
                    while (i < lines.size && '|' in lines[i]) rows += cells(lines[i++])
                    blocks += MdBlock.Table(header, rows)
                    continue
                }
                BULLET.matches(line) || ORDERED.matches(line) -> {
                    flushParagraph()
                    val bullet = BULLET.find(line)
                    val m = bullet ?: ORDERED.find(line)!!
                    val depth = m.groupValues[1].replace("\t", "    ").length / 2
                    val text = StringBuilder(m.groupValues[3])
                    // Lazy continuation lines belong to the item.
                    while (i + 1 < lines.size && lines[i + 1].isNotBlank() &&
                        !BULLET.matches(lines[i + 1]) && !ORDERED.matches(lines[i + 1]) &&
                        lines[i + 1].startsWith(" ")
                    ) text.append(' ').append(lines[++i].trim())
                    blocks += MdBlock.ListItem(bullet == null, m.groupValues[2], depth, text.toString())
                }
                else -> paragraph.append(trimmed).append(if (line.endsWith("  ")) "\n" else " ")
            }
            i++
        }
        flushParagraph()
        return blocks
    }

    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
}

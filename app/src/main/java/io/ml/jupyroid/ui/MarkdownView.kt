package io.ml.jupyroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.ml.jupyroid.output.Markdown
import io.ml.jupyroid.output.MdBlock
import io.ml.jupyroid.ui.theme.codeColors

@Composable
fun MarkdownView(source: String, modifier: Modifier = Modifier) {
    val blocks = remember(source) { Markdown.parse(source) }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = codeColors.editorBackground
    val typography = MaterialTheme.typography

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> Text(
                    inline(block.text, linkColor, codeBg),
                    style = when (block.level) {
                        1 -> typography.headlineMedium
                        2 -> typography.headlineSmall
                        3 -> typography.titleLarge
                        4 -> typography.titleMedium
                        else -> typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp),
                )
                is MdBlock.Paragraph -> Text(inline(block.text, linkColor, codeBg), style = typography.bodyLarge)
                is MdBlock.ListItem -> Row(Modifier.padding(start = (12 * block.depth).dp)) {
                    Text(
                        if (block.ordered) block.marker else "•",
                        style = typography.bodyLarge,
                        modifier = Modifier.width(24.dp),
                    )
                    Text(inline(block.text, linkColor, codeBg), style = typography.bodyLarge)
                }
                is MdBlock.Code -> CodeBlock(block.text, codeBg)
                is MdBlock.Math -> CodeBlock(block.text, codeBg, italic = true)
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                    Text(
                        inline(block.text, linkColor, codeBg),
                        style = typography.bodyLarge,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                is MdBlock.Table -> MdTable(block, linkColor, codeBg)
                MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun CodeBlock(text: String, background: Color, italic: Boolean = false) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(6.dp))
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        Text(text, style = CodeTextStyle, fontStyle = if (italic) FontStyle.Italic else null)
    }
}

@Composable
private fun MdTable(table: MdBlock.Table, linkColor: Color, codeBg: Color) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        val all = listOf(table.header) + table.rows
        all.forEachIndexed { r, row ->
            Row {
                table.header.indices.forEach { c ->
                    Text(
                        inline(row.getOrElse(c) { "" }, linkColor, codeBg),
                        fontWeight = if (r == 0) FontWeight.SemiBold else null,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.widthIn(min = 72.dp, max = 280.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            if (r == 0) HorizontalDivider()
        }
    }
}

private val INLINE = Regex(
    "(`[^`]+`)" +                       // code
        "|(\\*\\*[^*]+\\*\\*|__[^_]+__)" + // bold
        "|(~~[^~]+~~)" +                   // strike
        "|(!?\\[[^\\]]*]\\([^)]*\\))" +     // link / image
        "|(\\$[^$\\n]+\\$)" +              // inline math
        "|(\\*[^*\\s][^*]*\\*|(?<![\\w])_[^_\\s][^_]*_(?![\\w]))", // italic
)

/** Inline Markdown → AnnotatedString. Nesting is handled one level deep, which covers real notebooks. */
fun inline(text: String, linkColor: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(last, m.range.first))
        last = m.range.last + 1
        val v = m.value
        when {
            m.groups[1] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                append(v.trim('`'))
            }
            m.groups[2] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(inline(v.drop(2).dropLast(2), linkColor, codeBg))
            }
            m.groups[3] != null -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                append(v.drop(2).dropLast(2))
            }
            m.groups[4] != null -> {
                val label = v.substringAfter('[').substringBeforeLast("](")
                val url = v.substringAfterLast("](").dropLast(1).substringBefore(' ')
                if (v.startsWith("!")) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("[изображение: $label]") }
                } else {
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                        append(label)
                    }
                }
            }
            m.groups[5] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)) {
                append(v.trim('$'))
            }
            m.groups[6] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(inline(v.drop(1).dropLast(1), linkColor, codeBg))
            }
        }
    }
    append(text.substring(last))
}

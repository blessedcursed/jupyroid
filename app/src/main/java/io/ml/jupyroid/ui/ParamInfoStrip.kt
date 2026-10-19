package io.ml.jupyroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ml.jupyroid.R
import io.ml.jupyroid.editor.Signature

/**
 * IntelliJ-style parameter info (Ctrl+P): `read_csv(filepath, *, **sep: str = …**, delimiter=None, …) → DataFrame`
 * with the argument under the cursor highlighted and scrolled into view. Tap opens the docs.
 */
@Composable
fun ParamInfoStrip(signature: Signature, active: Int?, onDocs: () -> Unit, onClose: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val (text, ranges) = remember(signature, active, accent, dim) { render(signature, active, accent, dim) }
    val scroll = rememberScrollState()
    val layout = remember { arrayOfNulls<TextLayoutResult>(1) }

    LaunchedEffect(active, text) {
        val range = active?.let { ranges.getOrNull(it) } ?: return@LaunchedEffect
        val l = layout[0] ?: return@LaunchedEffect
        val left = l.getHorizontalPosition(range.first, usePrimaryDirection = true).toInt()
        val right = l.getHorizontalPosition(range.last + 1, usePrimaryDirection = true).toInt()
        // Only scroll when the parameter isn't fully visible; then align it to the left edge.
        if (left < scroll.value || right > scroll.value + scroll.viewportSize) {
            scroll.animateScrollTo(left.coerceAtLeast(0)) // the pinned "name(…" marks the cut
        }
    }

    Surface(color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_function), "Параметры",
                tint = accent, modifier = Modifier.padding(start = 10.dp, end = 6.dp).size(18.dp),
            )
            // The name stays put while the parameters scroll, so you always see what you're calling.
            Text(
                signature.name + "(" + if (scroll.value > 0) "…" else "",
                style = CodeTextStyle.copy(fontSize = 13.sp, lineHeight = 18.sp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Box(
                Modifier
                    .weight(1f)
                    .horizontalScroll(scroll)
                    .clickable(onClick = onDocs)
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    text,
                    style = CodeTextStyle.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    softWrap = false,
                    maxLines = 1,
                    onTextLayout = { layout[0] = it },
                )
            }
            Box(Modifier.size(40.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Clear, "Закрыть", Modifier.size(18.dp))
            }
        }
    }
}

/** Builds the label and remembers where each parameter starts/ends for scrolling. */
private fun render(sig: Signature, active: Int?, accent: Color, dim: Color): Pair<AnnotatedString, List<IntRange>> {
    val ranges = ArrayList<IntRange>()
    val text = buildAnnotatedString {
        if (sig.params.isEmpty()) withStyle(SpanStyle(color = dim)) { append("без параметров") }
        sig.params.forEachIndexed { i, p ->
            if (i > 0) append(", ")
            val start = length
            val style = when {
                i == active -> SpanStyle(color = accent, fontWeight = FontWeight.Bold, background = accent.copy(alpha = 0.12f))
                p == "*" || p == "/" -> SpanStyle(color = dim)
                else -> SpanStyle()
            }
            // Only the current parameter shows its full annotation; the rest stay skimmable.
            withStyle(style) { append(if (i == active) p else Signature.compact(p)) }
            ranges += start until length
        }
        append(")")
        sig.returns?.let { withStyle(SpanStyle(color = dim)) { append(" → $it") } }
    }
    return text to ranges
}

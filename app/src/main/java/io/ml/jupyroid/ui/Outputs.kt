package io.ml.jupyroid.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.ml.jupyroid.model.Output
import io.ml.jupyroid.model.jsonObjectOrNull
import io.ml.jupyroid.output.Ansi
import io.ml.jupyroid.output.AnsiColor
import io.ml.jupyroid.ui.theme.CodeColors
import io.ml.jupyroid.ui.theme.codeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Very long streams (training logs...) make Text layout slow; show only the tail. */
private const val MAX_TEXT_CHARS = 40_000

@Composable
fun OutputView(output: Output, modifier: Modifier = Modifier) {
    val colors = codeColors
    when (output) {
        is Output.Stream -> AnsiText(
            output.text,
            modifier.background(if (output.name == "stderr") colors.stderrBackground else Color.Transparent),
        )
        is Output.Error -> AnsiText(
            output.traceback.joinToString("\n").ifBlank { "${output.ename}: ${output.evalue}" },
            modifier.background(colors.errorBackground),
        )
        is Output.Rich -> RichOutput(output, modifier)
    }
}

@Composable
private fun RichOutput(output: Output.Rich, modifier: Modifier) {
    val png = output.text("image/png")
    val jpeg = output.text("image/jpeg")
    val html = output.text("text/html")
    val svg = output.text("image/svg+xml")
    val markdown = output.text("text/markdown")
    val plain = output.text("text/plain")
    when {
        png != null || jpeg != null -> {
            val meta = output.metadata[if (png != null) "image/png" else "image/jpeg"]?.jsonObjectOrNull()
            val width = meta?.get("width")?.jsonPrimitive?.intOrNull
            Base64Image((png ?: jpeg)!!, width, modifier)
        }
        html != null -> HtmlOutput(html, modifier)
        svg != null -> HtmlOutput(svg, modifier)
        markdown != null -> MarkdownView(markdown, modifier.padding(horizontal = 8.dp))
        plain != null -> AnsiText(plain, modifier)
        else -> Text(
            "[${output.data.keys.joinToString()}] — этот тип вывода пока не поддерживается",
            modifier.padding(8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun AnsiText(text: String, modifier: Modifier = Modifier) {
    val colors = codeColors
    val fallback = MaterialTheme.colorScheme.onSurface
    val annotated = remember(text, colors) { ansiToAnnotated(text, colors) }
    Box(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Text(annotated, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = CodeTextStyle, color = fallback)
    }
}

private fun ansiToAnnotated(raw: String, colors: CodeColors): AnnotatedString {
    val text = if (raw.length > MAX_TEXT_CHARS) "… (вывод обрезан)\n" + raw.takeLast(MAX_TEXT_CHARS) else raw
    fun color(c: AnsiColor?): Color? = when (c) {
        null -> null
        is AnsiColor.Indexed -> colors.ansi.getOrNull(c.index)
        is AnsiColor.Rgb -> Color(0xFF000000 or c.rgb.toLong())
    }
    return buildAnnotatedString {
        for (span in Ansi.parse(text.trimEnd('\n'))) {
            withStyle(SpanStyle(
                color = color(span.fg) ?: Color.Unspecified,
                background = color(span.bg)?.copy(alpha = 0.35f) ?: Color.Unspecified,
                fontWeight = if (span.bold) FontWeight.Bold else null,
            )) { append(span.text) }
        }
    }
}

@Composable
private fun Base64Image(data: String, declaredWidth: Int?, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, data) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val image = bitmap ?: return
    // Like a browser: one image pixel per CSS px (= dp), unless the kernel declared a size (retina figures).
    val width = (declaredWidth ?: image.width).dp
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .padding(4.dp)
            .widthIn(max = width)
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White),
    )
}

/**
 * HTML outputs (pandas tables, rich reprs) in a WebView sized to its content: the page reports
 * its height through a JS bridge, since a WebView inside a lazy list can't measure itself.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlOutput(html: String, modifier: Modifier) {
    var heightDp by remember { mutableFloatStateOf(24f) }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val borderColor = MaterialTheme.colorScheme.outlineVariant.toArgb()
    val document = remember(html, textColor) { wrapHtml(html, textColor, borderColor) }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.loadWithOverviewMode = false
                isVerticalScrollBarEnabled = false
                webViewClient = WebViewClient()
                addJavascriptInterface(HeightBridge { heightDp = it }, "JupyroidBridge")
            }
        },
        update = { webView ->
            if (webView.tag != document) {
                webView.tag = document
                webView.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.coerceIn(16f, 2000f).dp),
    )
}

private class HeightBridge(private val onHeight: (Float) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun height(px: Float) {
        main.post { onHeight(px) }
    }
}

private fun cssColor(argb: Int) =
    "rgba(${(argb shr 16) and 0xFF},${(argb shr 8) and 0xFF},${argb and 0xFF},${((argb ushr 24) / 255f)})"

private fun wrapHtml(body: String, textColor: Int, borderColor: Int) = """
<!doctype html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  html, body { margin: 0; padding: 0; background: transparent; color: ${cssColor(textColor)};
    font: 13px/1.4 -apple-system, Roboto, sans-serif; }
  #root { padding: 4px 8px; overflow-x: auto; display: inline-block; min-width: calc(100% - 16px); }
  table { border-collapse: collapse; font-size: 12px; font-variant-numeric: tabular-nums; }
  table { border: none !important; }
  th, td { padding: 4px 10px; text-align: right; border: none; border-bottom: 1px solid ${cssColor(borderColor)}; }
  th { font-weight: 600; }
  tbody tr:nth-child(odd) { background: rgba(127,127,127,0.08); }
  img, svg { max-width: 100%; height: auto; }
  pre { white-space: pre-wrap; }
</style></head><body><div id="root">$body</div>
<script>
  (function () {
    var report = function () { JupyroidBridge.height(document.getElementById('root').getBoundingClientRect().height); };
    new ResizeObserver(report).observe(document.getElementById('root'));
    window.addEventListener('load', report);
    report();
  })();
</script></body></html>
"""

@Composable
fun OutputsColumn(outputs: List<Output>, modifier: Modifier = Modifier) {
    if (outputs.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(top = 4.dp)) {
        outputs.forEach { OutputView(it) }
    }
}

package io.ml.jupyroid.ui

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ml.jupyroid.editor.PythonLexer
import io.ml.jupyroid.editor.TokenType
import io.ml.jupyroid.ui.theme.codeColors

val CodeTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp)
/** Inner padding of the editor; the cell needs it to place cursor rects. */
val EditorPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CodeEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    highlight: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onKey: (KeyEvent) -> Boolean,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    val colors = codeColors
    val bringIntoView = remember { BringIntoViewRequester() }
    val layout = remember { arrayOfNulls<TextLayoutResult>(1) }
    var focused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)

    // Keep the cursor line visible above the keyboard: when the IME opens it shrinks the
    // scrolling container, but nothing else scrolls the cursor back into view.
    LaunchedEffect(focused, imeBottom, value.selection, value.text.length) {
        if (!focused) return@LaunchedEffect
        delay(30) // let the text relayout first
        val l = layout[0] ?: return@LaunchedEffect
        val cursor = l.getCursorRect(value.selection.end.coerceAtMost(l.layoutInput.text.length))
        val padX = with(density) { EditorPadding.calculateLeftPadding(LayoutDirection.Ltr).toPx() }
        val padY = with(density) { EditorPadding.calculateTopPadding().toPx() }
        val margin = with(density) { 24.dp.toPx() }
        bringIntoView.bringIntoView(
            Rect(cursor.left + padX, cursor.top + padY - margin, cursor.right + padX, cursor.bottom + padY + margin),
        )
    }
    val transformation = remember(highlight, colors) {
        if (highlight) SyntaxHighlighting(colors.tokens) else VisualTransformation.None
    }
    NoSuggestionsInput {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = CodeTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = transformation,
        onTextLayout = { layout[0] = it },
        readOnly = readOnly,
        // No autocorrect / auto-capitalization: they mangle code.
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
        ),
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .padding(EditorPadding)
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent(onKey),
    )
    }
}

/**
 * Compose's KeyboardOptions can't stop Gboard & co. from autocorrecting (`json` → `Jason`).
 * Like Termux, we tell the IME this is a visible-password field with no suggestions, which
 * every mainstream keyboard honours, while keeping it multi-line so Enter inserts a newline.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NoSuggestionsInput(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val patched = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val connection = request.createInputConnection(outAttributes)
                    outAttributes.inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    return connection
                }
            }
            nextHandler.startInputMethod(patched)
        },
        content = content,
    )
}

/** Colors Python tokens; positions are unchanged, so the offset mapping is the identity. */
private class SyntaxHighlighting(private val palette: Map<TokenType, Color>) : VisualTransformation {
    private var cacheKey: String? = null
    private var cacheValue: AnnotatedString? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val highlighted = if (source == cacheKey) cacheValue!! else highlight(source).also {
            cacheKey = source
            cacheValue = it
        }
        return TransformedText(highlighted, OffsetMapping.Identity)
    }

    private fun highlight(source: String) = buildAnnotatedString {
        append(source)
        for (t in PythonLexer.tokenize(source)) {
            val color = palette[t.type] ?: continue
            val style = when (t.type) {
                TokenType.Comment -> SpanStyle(color = color, fontStyle = FontStyle.Italic)
                TokenType.Keyword -> SpanStyle(color = color, fontWeight = FontWeight.SemiBold)
                else -> SpanStyle(color = color)
            }
            addStyle(style, t.start, t.end)
        }
    }

    // VisualTransformation instances are compared to decide whether to re-run filter().
    override fun equals(other: Any?) = other is SyntaxHighlighting && other.palette == palette
    override fun hashCode() = palette.hashCode()
}

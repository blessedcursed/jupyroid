package io.ml.jupyroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.ml.jupyroid.editor.TokenType

/** Syntax and terminal colors, picked to read well on the Material surface in both themes. */
class CodeColors(
    val tokens: Map<TokenType, Color>,
    /** ANSI palette 0..15. */
    val ansi: List<Color>,
    val stderrBackground: Color,
    val errorBackground: Color,
    val editorBackground: Color,
)

private val Light = CodeColors(
    tokens = mapOf(
        TokenType.Keyword to Color(0xFF8A2BE2),
        TokenType.Builtin to Color(0xFF0B7A75),
        TokenType.Constant to Color(0xFF0550AE),
        TokenType.Str to Color(0xFFB2361F),
        TokenType.Comment to Color(0xFF6E7781),
        TokenType.Number to Color(0xFF0A7D2C),
        TokenType.Function to Color(0xFF0550AE),
        TokenType.ClassName to Color(0xFF0550AE),
        TokenType.Decorator to Color(0xFF9A6700),
        TokenType.Magic to Color(0xFF9A6700),
        TokenType.Self to Color(0xFF953800),
    ),
    ansi = listOf(
        0xFF24292F, 0xFFCF222E, 0xFF116329, 0xFF9A6700, 0xFF0550AE, 0xFF8250DF, 0xFF1B7C83, 0xFF6E7781,
        0xFF57606A, 0xFFA40E26, 0xFF1A7F37, 0xFF633C01, 0xFF218BFF, 0xFFA475F9, 0xFF3192AA, 0xFF8C959F,
    ).map(::Color),
    stderrBackground = Color(0xFFFFF1F0),
    errorBackground = Color(0xFFFFEBE9),
    editorBackground = Color(0xFFF6F8FA),
)

private val Dark = CodeColors(
    tokens = mapOf(
        TokenType.Keyword to Color(0xFFC792EA),
        TokenType.Builtin to Color(0xFF7FDBCA),
        TokenType.Constant to Color(0xFF79B8FF),
        TokenType.Str to Color(0xFFECC48D),
        TokenType.Comment to Color(0xFF7D8590),
        TokenType.Number to Color(0xFFF78C6C),
        TokenType.Function to Color(0xFF82AAFF),
        TokenType.ClassName to Color(0xFFFFCB6B),
        TokenType.Decorator to Color(0xFFFFCB6B),
        TokenType.Magic to Color(0xFFFFCB6B),
        TokenType.Self to Color(0xFFFF7B72),
    ),
    ansi = listOf(
        0xFF6E7681, 0xFFFF7B72, 0xFF7EE787, 0xFFE3B341, 0xFF79C0FF, 0xFFD2A8FF, 0xFF56D4DD, 0xFFB1BAC4,
        0xFF8B949E, 0xFFFFA198, 0xFF56D364, 0xFFF0D86F, 0xFFA5D6FF, 0xFFE2C5FF, 0xFFB3F0FF, 0xFFF0F6FC,
    ).map(::Color),
    stderrBackground = Color(0x33FF7B72),
    errorBackground = Color(0x26FF7B72),
    editorBackground = Color(0xFF161B22),
)

val codeColors: CodeColors
    @Composable @ReadOnlyComposable
    get() = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Dark else Light

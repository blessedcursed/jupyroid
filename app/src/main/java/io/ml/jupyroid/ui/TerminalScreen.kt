package io.ml.jupyroid.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.ml.jupyroid.workspace.TerminalTab

@Composable
fun TerminalScreen(tab: TerminalTab, onRestart: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { context ->
                    TerminalView(context, null).apply {
                        setTerminalViewClient(ViewClient(tab, this))
                        setTextSize(spToPx(context, tab.fontSizeSp))
                        setTypeface(Typeface.MONOSPACE)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        keepScreenOn = true
                        attachSession(tab.session)
                        tab.view = this
                    }
                },
                update = { view -> tab.view = view },
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            )
            if (tab.finished) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Процесс завершился (код ${tab.session.exitStatus})")
                        Button(onClick = onRestart) { Text("Новый терминал") }
                    }
                }
            }
        }
        TerminalKeys(tab)
    }
    // Open the keyboard right away for a fresh terminal.
    LaunchedEffect(tab) {
        tab.view?.let { view -> view.requestFocus(); showKeyboard(view) }
    }
    DisposableEffect(tab) {
        onDispose { tab.view = null }
    }
}

@Composable
private fun TerminalKeys(tab: TerminalTab) {
    fun key(label: String, code: Int) = ExtraKey(label) { tab.sendKey(code) }
    fun char(c: String) = ExtraKey(c) { tab.sendText(c) }
    ExtraKeysRow(
        listOf(
            key("Esc", KeyEvent.KEYCODE_ESCAPE),
            key("Tab", KeyEvent.KEYCODE_TAB),
            ExtraKey("Ctrl", active = tab.ctrl) { tab.ctrl = !tab.ctrl },
            ExtraKey("Alt", active = tab.alt) { tab.alt = !tab.alt },
            key("←", KeyEvent.KEYCODE_DPAD_LEFT),
            key("↓", KeyEvent.KEYCODE_DPAD_DOWN),
            key("↑", KeyEvent.KEYCODE_DPAD_UP),
            key("→", KeyEvent.KEYCODE_DPAD_RIGHT),
            key("Home", KeyEvent.KEYCODE_MOVE_HOME),
            key("End", KeyEvent.KEYCODE_MOVE_END),
            key("PgUp", KeyEvent.KEYCODE_PAGE_UP),
            key("PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
            char("-"), char("/"), char("|"), char("~"), char("$"), char("&"), char(">"), char("*"),
            ExtraKey("A−") { tab.zoom(-1) },
            ExtraKey("A+") { tab.zoom(+1) },
        ),
    )
}

/** Sends a special key through the view so it is encoded for the current terminal mode. */
private fun TerminalTab.sendKey(keyCode: Int) {
    val v = view ?: return
    var meta = 0
    if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    if (alt) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
    v.onKeyDown(keyCode, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
    ctrl = false
    alt = false
}

private fun TerminalTab.sendText(s: String) {
    val v = view ?: return
    s.codePoints().forEach { v.inputCodePoint(it, ctrl, alt) }
    ctrl = false
    alt = false
}

private fun TerminalTab.zoom(delta: Int) {
    fontSizeSp = (fontSizeSp + delta).coerceIn(8, 28)
    view?.let { it.setTextSize(spToPx(it.context, fontSizeSp)) }
}

private fun spToPx(context: Context, sp: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), context.resources.displayMetrics).toInt()

private fun showKeyboard(view: TerminalView) {
    view.post {
        view.context.getSystemService(InputMethodManager::class.java).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
}

/** Glue between TerminalView and our tab: keyboard, sticky modifiers from the extra keys, pinch zoom. */
private class ViewClient(private val tab: TerminalTab, private val view: TerminalView) : TerminalViewClient {
    private val main = Handler(Looper.getMainLooper())
    private var scale = 1f

    override fun onScale(scale: Float): Float {
        // Pinch-zoom: accumulate until a full font step, then resize.
        this.scale *= scale
        if (this.scale > 1.15f || this.scale < 0.87f) {
            tab.zoom(if (this.scale > 1f) 1 else -1)
            this.scale = 1f
        }
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        view.requestFocus()
        showKeyboard(view)
    }

    override fun shouldBackButtonBeMappedToEscape() = false
    // Gboard & co. otherwise buffer words (and autocorrect them) before sending.
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false

    // The sticky modifiers apply to the next key typed on the soft keyboard; the view may ask
    // more than once for one key, so they're cleared on the next loop iteration.
    override fun readControlKey(): Boolean = tab.ctrl.also { if (it) main.post { tab.ctrl = false } }
    override fun readAltKey(): Boolean = tab.alt.also { if (it) main.post { tab.alt = false } }
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
    override fun onEmulatorSet() = Unit

    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}

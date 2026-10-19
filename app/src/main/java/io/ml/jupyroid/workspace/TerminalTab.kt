package io.ml.jupyroid.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import io.ml.jupyroid.runtime.Runtime

/**
 * A bash shell inside the embedded Debian. It talks to the process through a real pty
 * (Termux's terminal-emulator), so it works even when Jupyter itself is broken — which is
 * exactly when you need a terminal to fix the environment.
 */
class TerminalTab(
    number: Int,
    runtime: Runtime,
    context: Context,
    /** Shell command to run first; the shell stays open afterwards. */
    command: String? = null,
    label: String? = null,
) : WorkspaceTab, TerminalSessionClient {
    private val appContext = context.applicationContext

    override val id: String = "terminal:$number"
    override val title: String = label ?: "Терминал $number"
    override val persistPath: String? = null
    override val dirty: Boolean = false
    override var message by mutableStateOf<String?>(null)

    var finished by mutableStateOf(false)
        private set

    /** Sticky modifiers toggled from the extra-keys row; consumed by the next key. */
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    var fontSizeSp = 14

    /** The view currently showing this session (views come and go with tab switches). */
    var view: TerminalView? = null

    val session: TerminalSession = run {
        val shell = if (command == null) {
            listOf("/bin/bash", "--login")
        } else {
            listOf("/bin/bash", "--login", "-c", "$command; echo; echo '[готово — терминал остаётся открытым]'; exec bash --login")
        }
        val argv = runtime.guestCommand(shell)
        val env = (runtime.hostEnv() + ("PATH" to "/system/bin")).map { (k, v) -> "$k=$v" }
        TerminalSession(argv[0], runtime.base.path, argv.toTypedArray(), env.toTypedArray(), TRANSCRIPT_ROWS, this)
    }

    override fun save(quiet: Boolean) = Unit
    override suspend fun saveNow(quiet: Boolean) = Unit
    override fun dispose() = session.finishIfRunning()

    // ---- TerminalSessionClient -----------------------------------------------------------

    override fun onTextChanged(changedSession: TerminalSession) {
        view?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        finished = true
        view?.invalidate()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
        message = "Скопировано"
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString() ?: return
        this.session.emulator?.paste(text)
    }

    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) { view?.invalidate() }
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message.orEmpty()) }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message.orEmpty()) }
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message, e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }

    companion object {
        private const val TAG = "JupyroidTerminal"
        private const val TRANSCRIPT_ROWS = 5000

        /** Single-quotes a string for bash. */
        fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}

package io.ml.jupyroid.data

import io.ml.jupyroid.model.Output
import io.ml.jupyroid.model.jsonObjectOrNull
import io.ml.jupyroid.model.stringOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Instant
import java.util.UUID

enum class KernelState(val label: String) {
    Connecting("подключение"),
    Starting("запуск"),
    Idle("готово"),
    Busy("занято"),
    Restarting("перезапуск"),
    Dead("ядро умерло"),
    Disconnected("нет связи"),
}

sealed interface ExecEvent {
    data class Out(val output: Output) : ExecEvent
    data class ClearOutput(val wait: Boolean) : ExecEvent
    data class ExecutionCount(val count: Int?) : ExecEvent
    /** Execution finished; [status] is "ok", "error" or "aborted" (or "cancelled" if the kernel went away). */
    data class Done(val status: String) : ExecEvent
}

data class Completion(val matches: List<String>, val cursorStart: Int, val cursorEnd: Int)

/**
 * One kernel's `/api/kernels/{id}/channels` websocket, using the default JSON wire format
 * of the Jupyter messaging protocol (https://jupyter-client.readthedocs.io/en/latest/messaging.html).
 *
 * All callbacks are delivered on the main thread, in message order.
 */
class KernelClient(
    private val api: JupyterApi,
    val kernelId: String,
    private val scope: CoroutineScope,
) {
    /** Kept across reconnects, so the server replays messages buffered while we were away. */
    private val sessionId = UUID.randomUUID().toString()

    private val _state = MutableStateFlow(KernelState.Connecting)
    val state: StateFlow<KernelState> = _state

    private var socket: WebSocket? = null
    private var closed = false
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private var pump: Job? = null

    private class Execution(val onEvent: (ExecEvent) -> Unit) {
        var replyStatus: String? = null
        var idle = false
    }

    /** Main-thread only. */
    private val executions = LinkedHashMap<String, Execution>()
    private val replies = HashMap<String, CompletableDeferred<JsonObject>>()

    /** update_display_data can target outputs of any earlier execution, so it bypasses [executions]. */
    var onUpdateDisplay: ((Output.Rich) -> Unit)? = null

    fun connect() {
        pump = scope.launch(Dispatchers.Main) {
            for (text in incoming) runCatching { dispatch(Json.parseToJsonElement(text).jsonObject) }
        }
        open()
    }

    private fun open() {
        if (closed) return
        _state.value = KernelState.Connecting
        val request = Request.Builder()
            .url(api.kernelChannelsUrl(kernelId, sessionId))
            .header("Authorization", "token ${api.config.token}")
            .build()
        socket = api.http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                send("shell", "kernel_info_request", JsonObject(emptyMap()))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                incoming.trySend(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
        })
    }

    private fun lost(webSocket: WebSocket) {
        scope.launch(Dispatchers.Main) {
            if (webSocket !== socket || closed) return@launch
            socket = null
            _state.value = KernelState.Disconnected
            delay(RECONNECT_DELAY_MS)
            open()
        }
    }

    fun close() {
        closed = true
        socket?.close(1000, null)
        socket = null
        pump?.cancel()
        finishAll("cancelled")
    }

    /** Main thread. Returns the request's msg_id. */
    fun execute(code: String, onEvent: (ExecEvent) -> Unit): String {
        val content = buildJsonObject {
            put("code", code)
            put("silent", false)
            put("store_history", true)
            put("user_expressions", JsonObject(emptyMap()))
            put("allow_stdin", false)
            put("stop_on_error", true)
        }
        val msgId = send("shell", "execute_request", content)
        if (msgId == null) {
            onEvent(ExecEvent.Done("cancelled"))
        } else {
            executions[msgId] = Execution(onEvent)
        }
        return msgId ?: ""
    }

    /** [cursorPos] is a UTF-16 index into [code]; the reply's range is converted back to UTF-16. */
    suspend fun complete(code: String, cursorPos: Int): Completion? {
        val reply = request("complete_request", buildJsonObject {
            put("code", code)
            put("cursor_pos", code.codePointCount(0, cursorPos))
        }) ?: return null
        if (reply["status"]?.stringOrNull() != "ok") return null
        val start = reply["cursor_start"]?.jsonPrimitive?.intOrNull ?: return null
        val end = reply["cursor_end"]?.jsonPrimitive?.intOrNull ?: return null
        val toUtf16 = { cp: Int -> runCatching { code.offsetByCodePoints(0, cp) }.getOrDefault(cursorPos) }
        return Completion(
            matches = reply["matches"]?.jsonArray.orEmpty().mapNotNull { it.stringOrNull() },
            cursorStart = toUtf16(start),
            cursorEnd = toUtf16(end),
        )
    }

    /** Returns the plain-text docstring/signature for the object at the cursor, if any. */
    suspend fun inspect(code: String, cursorPos: Int): String? {
        val reply = request("inspect_request", buildJsonObject {
            put("code", code)
            put("cursor_pos", code.codePointCount(0, cursorPos))
            put("detail_level", 0)
        }) ?: return null
        if (reply["found"]?.jsonPrimitive?.booleanOrNull != true) return null
        return reply["data"]?.jsonObjectOrNull()?.get("text/plain")?.stringOrNull()
    }

    private suspend fun request(type: String, content: JsonObject): JsonObject? {
        val deferred = CompletableDeferred<JsonObject>()
        val msgId = send("shell", type, content) ?: return null
        replies[msgId] = deferred
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) { deferred.await() }.also { replies.remove(msgId) }
    }

    private fun send(channel: String, type: String, content: JsonObject): String? {
        val ws = socket ?: return null
        val msgId = UUID.randomUUID().toString()
        val msg = buildJsonObject {
            put("header", buildJsonObject {
                put("msg_id", msgId)
                put("username", "jupyroid")
                put("session", sessionId)
                put("msg_type", type)
                put("version", "5.3")
                put("date", Instant.now().toString())
            })
            put("parent_header", JsonObject(emptyMap()))
            put("metadata", JsonObject(emptyMap()))
            put("content", content)
            put("channel", channel)
            put("buffers", JsonArray(emptyList()))
        }
        return if (ws.send(msg.toString())) msgId else null
    }

    private fun dispatch(msg: JsonObject) {
        val type = msg["header"]?.jsonObjectOrNull()?.get("msg_type")?.stringOrNull() ?: return
        val parentId = msg["parent_header"]?.jsonObjectOrNull()?.get("msg_id")?.stringOrNull()
        val content = msg["content"]?.jsonObjectOrNull() ?: JsonObject(emptyMap())
        val execution = parentId?.let { executions[it] }

        when (type) {
            "status" -> {
                val state = when (content["execution_state"]?.stringOrNull()) {
                    "idle" -> KernelState.Idle
                    "busy" -> KernelState.Busy
                    "starting" -> KernelState.Starting
                    "restarting" -> KernelState.Restarting
                    "dead" -> KernelState.Dead
                    else -> null
                }
                if (state != null) _state.value = state
                if (state == KernelState.Restarting || state == KernelState.Dead) finishAll("cancelled")
                if (state == KernelState.Idle && execution != null) {
                    execution.idle = true
                    finishIfComplete(parentId, execution)
                }
            }
            "kernel_info_reply" -> {
                if (_state.value == KernelState.Connecting) _state.value = KernelState.Idle
                replies[parentId]?.complete(content)
            }
            "execute_input" ->
                execution?.onEvent?.invoke(ExecEvent.ExecutionCount(content["execution_count"]?.jsonPrimitive?.intOrNull))
            "update_display_data" ->
                (Output.fromJson(content, type) as? Output.Rich)?.let { onUpdateDisplay?.invoke(it) }
            "stream", "display_data", "execute_result", "error" ->
                Output.fromJson(content, type)?.let { execution?.onEvent?.invoke(ExecEvent.Out(it)) }
            "clear_output" ->
                execution?.onEvent?.invoke(ExecEvent.ClearOutput(content["wait"]?.jsonPrimitive?.booleanOrNull == true))
            "execute_reply" -> if (execution != null) {
                execution.replyStatus = content["status"]?.stringOrNull() ?: "ok"
                execution.onEvent(ExecEvent.ExecutionCount(content["execution_count"]?.jsonPrimitive?.intOrNull))
                finishIfComplete(parentId, execution)
            }
            else -> replies[parentId]?.complete(content)
        }
    }

    /** An execution is over once both its execute_reply and its trailing idle status arrived. */
    private fun finishIfComplete(msgId: String, execution: Execution) {
        val status = execution.replyStatus ?: return
        if (!execution.idle) return
        executions.remove(msgId)
        execution.onEvent(ExecEvent.Done(status))
    }

    /** Ends all pending executions, e.g. after a kernel restart the server never answers them. */
    fun finishAll(status: String) {
        val pending = executions.values.toList()
        executions.clear()
        pending.forEach { it.onEvent(ExecEvent.Done(status)) }
    }

    companion object {
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val REQUEST_TIMEOUT_MS = 10_000L
    }
}

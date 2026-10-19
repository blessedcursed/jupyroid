package io.ml.jupyroid.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * nbformat 4 notebook. Everything we don't model explicitly (cell/notebook metadata,
 * attachments, output metadata) is kept as raw JSON so a load/save round trip through
 * Jupyroid doesn't lose anything JupyterLab wrote.
 */
data class Notebook(
    val cells: List<Cell>,
    val metadata: JsonObject,
    val nbformat: Int = 4,
    val nbformatMinor: Int = 5,
) {
    val kernelName: String?
        get() = metadata["kernelspec"]?.jsonObjectOrNull()?.get("name")?.stringOrNull()

    val language: String
        get() = metadata["language_info"]?.jsonObjectOrNull()?.get("name")?.stringOrNull()
            ?: metadata["kernelspec"]?.jsonObjectOrNull()?.get("language")?.stringOrNull()
            ?: "python"

    fun toJson(): JsonObject = buildJsonObject {
        put("cells", JsonArray(cells.map { it.toJson(nbformatMinor) }))
        put("metadata", metadata)
        put("nbformat", nbformat)
        put("nbformat_minor", nbformatMinor)
    }

    companion object {
        fun fromJson(json: JsonObject): Notebook {
            val minor = json["nbformat_minor"]?.jsonPrimitive?.intOrNull ?: 5
            return Notebook(
                cells = json["cells"]?.jsonArray.orEmpty().map { Cell.fromJson(it.jsonObject) },
                metadata = json["metadata"]?.jsonObjectOrNull() ?: JsonObject(emptyMap()),
                nbformat = json["nbformat"]?.jsonPrimitive?.intOrNull ?: 4,
                nbformatMinor = minor,
            )
        }

        fun empty(): Notebook = Notebook(listOf(Cell.new(CellType.Code)), JsonObject(emptyMap()))
    }
}

enum class CellType(val json: String) {
    Code("code"), Markdown("markdown"), Raw("raw");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.json == s } ?: Raw
    }
}

data class Cell(
    val id: String,
    val type: CellType,
    val source: String,
    val metadata: JsonObject = JsonObject(emptyMap()),
    val outputs: List<Output> = emptyList(),
    val executionCount: Int? = null,
    /** Markdown/raw cell attachments, passed through untouched. */
    val attachments: JsonElement? = null,
) {
    fun toJson(nbformatMinor: Int = 5): JsonObject = buildJsonObject {
        // Cell ids only exist from nbformat 4.5 on; older files must not get them.
        if (nbformatMinor >= 5) put("id", id)
        put("cell_type", type.json)
        put("metadata", metadata)
        put("source", multilineJson(source))
        if (attachments != null) put("attachments", attachments)
        if (type == CellType.Code) {
            put("execution_count", executionCount?.let { JsonPrimitive(it) } ?: JsonNull)
            put("outputs", JsonArray(outputs.map { it.toJson() }))
        }
    }

    companion object {
        fun new(type: CellType, source: String = "") = Cell(newCellId(), type, source)

        fun fromJson(json: JsonObject): Cell {
            val type = CellType.of(json["cell_type"]?.stringOrNull())
            return Cell(
                id = json["id"]?.stringOrNull() ?: newCellId(),
                type = type,
                source = json["source"].multiline(),
                metadata = json["metadata"]?.jsonObjectOrNull() ?: JsonObject(emptyMap()),
                outputs = json["outputs"]?.jsonArray.orEmpty().mapNotNull { Output.fromJson(it.jsonObject) },
                executionCount = json["execution_count"]?.jsonPrimitive?.intOrNull,
                attachments = json["attachments"],
            )
        }
    }
}

sealed interface Output {
    fun toJson(): JsonObject

    data class Stream(val name: String, val text: String) : Output {
        override fun toJson() = buildJsonObject {
            put("output_type", "stream")
            put("name", name)
            put("text", multilineJson(text))
        }
    }

    /** display_data / execute_result / update_display_data: a mime bundle. */
    data class Rich(
        val data: JsonObject,
        val metadata: JsonObject = JsonObject(emptyMap()),
        val executionCount: Int? = null,
        val isResult: Boolean = false,
        val displayId: String? = null,
    ) : Output {
        /** Text for a mime type; multiline arrays are joined, JSON payloads stringified. */
        fun text(mime: String): String? {
            val v = data[mime] ?: return null
            if (v == JsonNull) return null
            return if (v is JsonObject || mime.endsWith("json")) v.toString() else v.multiline()
        }

        override fun toJson() = buildJsonObject {
            put("output_type", if (isResult) "execute_result" else "display_data")
            // Kernels send text as one string; on disk text/* is a list of lines like JupyterLab writes.
            put("data", JsonObject(data.mapValues { (mime, v) ->
                if (mime.startsWith("text/") && v is JsonPrimitive && v.isString) multilineJson(v.content) else v
            }))
            put("metadata", metadata)
            if (isResult) put("execution_count", executionCount?.let { JsonPrimitive(it) } ?: JsonNull)
        }
    }

    data class Error(val ename: String, val evalue: String, val traceback: List<String>) : Output {
        override fun toJson() = buildJsonObject {
            put("output_type", "error")
            put("ename", ename)
            put("evalue", evalue)
            put("traceback", JsonArray(traceback.map { JsonPrimitive(it) }))
        }
    }

    companion object {
        /** Parses an nbformat output or the content of a kernel iopub message of the same type. */
        fun fromJson(json: JsonObject, outputType: String? = null): Output? =
            when (outputType ?: json["output_type"]?.stringOrNull()) {
                "stream" -> Stream(json["name"]?.stringOrNull() ?: "stdout", json["text"].multiline())
                "display_data", "update_display_data" -> Rich(
                    data = json["data"]?.jsonObjectOrNull() ?: JsonObject(emptyMap()),
                    metadata = json["metadata"]?.jsonObjectOrNull() ?: JsonObject(emptyMap()),
                    displayId = json["transient"]?.jsonObjectOrNull()?.get("display_id")?.stringOrNull(),
                )
                "execute_result" -> Rich(
                    data = json["data"]?.jsonObjectOrNull() ?: JsonObject(emptyMap()),
                    metadata = json["metadata"]?.jsonObjectOrNull() ?: JsonObject(emptyMap()),
                    executionCount = json["execution_count"]?.jsonPrimitive?.intOrNull,
                    isResult = true,
                    displayId = json["transient"]?.jsonObjectOrNull()?.get("display_id")?.stringOrNull(),
                )
                "error" -> Error(
                    ename = json["ename"]?.stringOrNull().orEmpty(),
                    evalue = json["evalue"]?.stringOrNull().orEmpty(),
                    traceback = json["traceback"]?.jsonArray.orEmpty().mapNotNull { it.stringOrNull() },
                )
                else -> null
            }
    }
}

/**
 * Appends stream text the way a terminal (and JupyterLab) shows it: consecutive writes to
 * the same stream merge, and `\r` rewinds to the start of the line so tqdm-style
 * progress bars update in place instead of piling up.
 */
fun appendStreamText(old: String, new: String): String {
    val combined = old + new
    if ('\r' !in combined) return combined
    return combined.split('\n').joinToString("\n") { line ->
        // A trailing \r only moves the cursor; the line stays visible until overwritten.
        line.trimEnd('\r').substringAfterLast('\r')
    }
}

fun newCellId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

/** nbformat stores multiline strings either as a string or a list of lines. */
fun JsonElement?.multiline(): String = when (this) {
    null, JsonNull -> ""
    is JsonArray -> joinToString("") { it.stringOrNull().orEmpty() }
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonObject -> toString()
}

/** The canonical on-disk form: a list of lines, each keeping its trailing newline. */
fun multilineJson(text: String): JsonArray {
    if (text.isEmpty()) return JsonArray(emptyList())
    val lines = mutableListOf<JsonPrimitive>()
    var start = 0
    while (start < text.length) {
        val nl = text.indexOf('\n', start)
        val end = if (nl < 0) text.length else nl + 1
        lines += JsonPrimitive(text.substring(start, end))
        start = end
    }
    return JsonArray(lines)
}

fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
